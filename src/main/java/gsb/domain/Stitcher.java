package gsb.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic interference-event stitcher.
 *
 * <p>All comparisons use corrected half-open windows: a link is only created
 * when the real intersection of the two swept frequency ranges over a real
 * time intersection has positive width. Candidate generation is order-free;
 * the result depends only on the input records, so parallel/chunked reordering
 * cannot change the fingerprints.
 */
public final class Stitcher {

    private Stitcher() {
    }

    private static final double SCORE_EPS = 1e-9;

    /** Apply a versioned device model (+ optional segment fine tuning) to a raw fragment. */
    public static CorrectedSegment applyCorrection(
            RawSegment raw, DeviceCorrection model, SegmentOverride override) {
        double ppm = raw.clockDriftPpm() + (model == null ? 0.0 : model.clockPpm());
        double tOff = (model == null ? 0.0 : model.clockOffsetMs())
                + (override == null ? 0.0 : override.clockOffsetMs());
        double loOff = (model == null ? raw.loOffsetHz() : raw.loOffsetHz() + model.loOffsetHz())
                + (override == null ? 0.0 : override.loOffsetHz());
        double gain = model == null ? 0.0 : model.gainDb();
        double scale = 1.0 + ppm / 1_000_000.0;
        double t0 = raw.tStartMs() * scale + tOff;
        double t1 = raw.tEndMs() * scale + tOff;
        double f0 = raw.fStartHz() - loOff;
        double f1 = f0 + raw.binHz() * raw.buckets().size();
        boolean extrapolated = model != null
                && (raw.temperatureC() < model.validFromTempC()
                || raw.temperatureC() > model.validToTempC());
        List<Double> calibrated = new ArrayList<>(raw.buckets().size());
        for (double b : raw.buckets()) {
            calibrated.add(b + gain);
        }
        return new CorrectedSegment(raw, t0, t1, f0, f1, raw.binHz(), ppm, loOff, gain,
                extrapolated, calibrated);
    }

    /**
     * Build the deterministic link candidate between two corrected fragments.
     * Returns {@code null} unless the corrected half-open sweep windows overlap
     * in both frequency and time with positive width.
     */
    public static LinkEdge buildEdge(CorrectedSegment a, CorrectedSegment b) {
        double fLo = Math.max(a.cFStartHz(), b.cFStartHz());
        double fHi = Math.min(a.cFEndHz(), b.cFEndHz());
        double tLo = Math.max(a.cTStartMs(), b.cTStartMs());
        double tHi = Math.min(a.cTEndMs(), b.cTEndMs());
        if (!(fHi > fLo) || !(tHi > tLo)) {
            return null;
        }
        int samples = 32;
        double[] pa = new double[samples];
        double[] pb = new double[samples];
        double eA = 0.0;
        double eB = 0.0;
        for (int i = 0; i < samples; i++) {
            double x0 = fLo + (fHi - fLo) * i / samples;
            double x1 = fLo + (fHi - fLo) * (i + 1) / samples;
            pa[i] = a.meanPower(x0, x1);
            pb[i] = b.meanPower(x0, x1);
            eA += pa[i] * (x1 - x0) * (tHi - tLo);
            eB += pb[i] * (x1 - x0) * (tHi - tLo);
        }
        double corr = pearson(pa, pb);
        double minSpan = Math.min(a.cFEndHz() - a.cFStartHz(), b.cFEndHz() - b.cFStartHz());
        double fCoverage = (fHi - fLo) / minSpan;
        double minDur = Math.min(a.cTEndMs() - a.cTStartMs(), b.cTEndMs() - b.cTStartMs());
        double tCoverage = (tHi - tLo) / minDur;
        double clockUncMs = 0.0;
        boolean cross = !a.device().equals(b.device());
        if (cross) {
            double ppmSpan = Math.abs(a.cClockDriftPpm() - b.cClockDriftPpm());
            double ageMs = tHi - tLo;
            clockUncMs = ppmSpan / 1_000_000.0 * Math.max(a.cTEndMs(), b.cTEndMs())
                    + 0.5 * ppmSpan / 1_000_000.0 * ageMs;
        }
        double energyAgree = energyAgreement(eA, eB);
        double score = 0.60 * Math.max(0.0, corr)
                + 0.20 * fCoverage
                + 0.10 * tCoverage
                + 0.10 * energyAgree;
        if (cross) {
            score -= 0.05 * Math.min(1.0, clockUncMs / 50.0);
        }
        score = clamp01(score);
        String reason = String.format(
                "corr=%.3f fCov=%.3f tCov=%.3f eAgree=%.3f clockUncMs=%.3f%s%s",
                corr, fCoverage, tCoverage, energyAgree, clockUncMs,
                clockUncMs >= 50.0 ? " [clock-indistinguishable]" : "",
                a.extrapolated() || b.extrapolated() ? " [extrapolated-correction]" : "");
        String idA = a.id();
        String idB = b.id();
        return new LinkEdge(
                LinkEdge.idOf(idA, idB),
                LinkEdge.idOf(idA, idB).startsWith(idA) ? idA : idB,
                LinkEdge.idOf(idA, idB).startsWith(idA) ? idB : idA,
                a.device(), b.device(), cross,
                fLo, fHi, tLo, tHi, fHi - fLo, tHi - tLo,
                eA, eB, corr, clockUncMs, score, reason);
    }

    private static double pearson(double[] x, double[] y) {
        double mx = 0.0;
        double my = 0.0;
        for (int i = 0; i < x.length; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= x.length;
        my /= y.length;
        double num = 0.0;
        double dx = 0.0;
        double dy = 0.0;
        for (int i = 0; i < x.length; i++) {
            double a = x[i] - mx;
            double b = y[i] - my;
            num += a * b;
            dx += a * a;
            dy += b * b;
        }
        if (dx <= 0 || dy <= 0) {
            return 0.0;
        }
        return clamp(num / Math.sqrt(dx * dy), -1.0, 1.0);
    }

    private static double energyAgreement(double eA, double eB) {
        double hi = Math.max(eA, eB);
        if (hi <= 0) {
            return 0.0;
        }
        return Math.min(eA, eB) / hi;
    }

    // ------------------------------------------------------------------
    // Deterministic candidate generation + greedy union + event assembly
    // ------------------------------------------------------------------

    public record StitchInput(
            List<CorrectedSegment> segments,
            java.util.Set<String> rejectedEdgeIds,
            java.util.List<java.util.Set<String>> splitBarriers,
            double autoThreshold) {
    }

    public record StitchResult(List<StitchedEvent> events, List<LinkEdge> candidates) {
    }

    /**
     * All candidate edges. Pair generation uses index ranges so parallel
     * chunking only changes scheduling; the collected set is fully ordered
     * afterwards by (score desc, edgeId asc).
     */
    public static List<LinkEdge> candidateEdges(List<CorrectedSegment> segments) {
        List<CorrectedSegment> ordered = segments.stream()
                .sorted(Comparator.comparing(CorrectedSegment::id))
                .toList();
        int n = ordered.size();
        List<LinkEdge> edges = java.util.stream.IntStream.range(0, n)
                .parallel()
                .boxed()
                .flatMap(i -> java.util.stream.IntStream.range(i + 1, n)
                        .mapToObj(j -> buildEdge(ordered.get(i), ordered.get(j))))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());
        edges.sort(Comparator.comparingDouble(LinkEdge::score).reversed()
                .thenComparing(LinkEdge::edgeId));
        return edges;
    }

    public static StitchResult stitch(StitchInput input) {
        List<CorrectedSegment> segs = input.segments().stream()
                .sorted(Comparator.comparing(CorrectedSegment::id))
                .toList();
        Map<String, CorrectedSegment> byId = new HashMap<>();
        for (CorrectedSegment s : segs) {
            byId.put(s.id(), s);
        }
        List<LinkEdge> candidates = candidateEdges(segs);

        int n = segs.size();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(segs.get(i).id(), i);
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        Map<Integer, List<LinkEdge>> rootEdges = new HashMap<>();
        Map<Integer, List<LinkEdge>> rootCompeting = new HashMap<>();
        java.util.Set<String> consumed = new java.util.HashSet<>();

        for (int k = 0; k < candidates.size(); k++) {
            LinkEdge edge = candidates.get(k);
            if (consumed.contains(edge.edgeId())) {
                continue;
            }
            if (input.rejectedEdgeIds().contains(edge.edgeId())) {
                continue;
            }
            if (edge.score() + SCORE_EPS < input.autoThreshold()) {
                continue;
            }
            int ra = find(parent, index.get(edge.segmentA()));
            int rb = find(parent, index.get(edge.segmentB()));
            if (ra == rb) {
                continue;
            }
            if (barred(input.splitBarriers(), parentMembers(parent, ra), parentMembers(parent, rb))) {
                continue;
            }
            // Lower-scored proposals between the same two groups are competing
            // evidence: keep them with the event, never silently overwrite.
            List<LinkEdge> alternatives = new ArrayList<>();
            for (int k2 = k + 1; k2 < candidates.size(); k2++) {
                LinkEdge other = candidates.get(k2);
                if (consumed.contains(other.edgeId())
                        || input.rejectedEdgeIds().contains(other.edgeId())) {
                    continue;
                }
                int oa = find(parent, index.get(other.segmentA()));
                int ob = find(parent, index.get(other.segmentB()));
                if ((oa == ra && ob == rb) || (oa == rb && ob == ra)) {
                    alternatives.add(other);
                    consumed.add(other.edgeId());
                }
            }
            int merged = union(parent, ra, rb);
            rootEdges.computeIfAbsent(merged, x -> new ArrayList<>()).add(edge);
            rootCompeting.computeIfAbsent(merged, x -> new ArrayList<>()).addAll(alternatives);
        }

        Map<Integer, List<Integer>> components = new HashMap<>();
        for (int i = 0; i < n; i++) {
            components.computeIfAbsent(find(parent, i), x -> new ArrayList<>()).add(i);
        }
        List<StitchedEvent> events = new ArrayList<>();
        for (List<Integer> members : components.values()) {
            List<CorrectedSegment> ms = members.stream()
                    .map(segs::get)
                    .sorted(Comparator.comparingDouble(CorrectedSegment::cTStartMs)
                            .thenComparing(CorrectedSegment::id))
                    .toList();
            events.add(buildEvent(ms, rootEdges, rootCompeting));
        }
        events.sort(Comparator.comparingDouble(StitchedEvent::startMs)
                .thenComparing(StitchedEvent::fingerprint));
        return new StitchResult(events, candidates);
    }

    private static boolean barred(List<java.util.Set<String>> barriers,
                                  List<String> groupA, List<String> groupB) {
        for (java.util.Set<String> barrier : barriers) {
            boolean inA = false;
            boolean inB = false;
            for (String s : groupA) {
                if (barrier.contains(s)) {
                    inA = true;
                }
            }
            for (String s : groupB) {
                if (barrier.contains(s)) {
                    inB = true;
                }
            }
            if (inA && inB) {
                return true;
            }
        }
        return false;
    }

    private static List<String> parentMembers(int[] parent, int root) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < parent.length; i++) {
            if (find(parent, i) == root) {
                out.add(String.valueOf(i));
            }
        }
        return out;
    }

    private static StitchedEvent buildEvent(
            List<CorrectedSegment> members,
            Map<Integer, List<LinkEdge>> rootEdges,
            Map<Integer, List<LinkEdge>> rootCompeting) {
        List<String> ids = members.stream().map(CorrectedSegment::id).sorted().toList();
        List<String> devices = members.stream().map(CorrectedSegment::device)
                .distinct().sorted().toList();
        boolean cross = devices.size() > 1;

        double startMs = Double.POSITIVE_INFINITY;
        double endMs = Double.NEGATIVE_INFINITY;
        double startHz = Double.POSITIVE_INFINITY;
        double endHz = Double.NEGATIVE_INFINITY;
        double totalDuration = 0.0;
        double satDuration = 0.0;
        double extrapDuration = 0.0;
        double energy = 0.0;
        for (CorrectedSegment s : members) {
            startMs = Math.min(startMs, s.cTStartMs());
            endMs = Math.max(endMs, s.cTEndMs());
            startHz = Math.min(startHz, s.cFStartHz());
            endHz = Math.max(endHz, s.cFEndHz());
            double dur = s.cTEndMs() - s.cTStartMs();
            totalDuration += dur;
            if (s.raw().saturated()) {
                satDuration += dur;
            }
            if (s.extrapolated()) {
                extrapDuration += dur;
            }
            energy += s.meanPower(s.cFStartHz(), s.cFEndHz())
                    * (s.cFEndHz() - s.cFStartHz()) * dur;
        }
        double gapMs = (endMs - startMs) - coveredTime(members);
        double satFrac = totalDuration > 0 ? satDuration / totalDuration : 0.0;
        double extrapFrac = totalDuration > 0 ? extrapDuration / totalDuration : 0.0;

        List<LinkEdge> edges = new ArrayList<>();
        List<LinkEdge> competing = new ArrayList<>();
        // Edges are stored under roots that cease to exist after path
        // compression; gather everything attached to member positions via a
        // second pass keyed by the component's accepted-edge set.
        gatherMemberEdges(members, rootEdges, edges);
        gatherMemberEdges(members, rootCompeting, competing);
        edges.sort(Comparator.comparingDouble(LinkEdge::score).reversed()
                .thenComparing(LinkEdge::edgeId));
        competing.sort(Comparator.comparingDouble(LinkEdge::score).reversed()
                .thenComparing(LinkEdge::edgeId));

        double clockMs = edges.stream().mapToDouble(LinkEdge::clockUncertaintyMs).max().orElse(0.0);
        double corrArea = 0.0;
        double area = 0.0;
        for (LinkEdge e : edges) {
            double w = e.overlapHz() * e.overlapMs();
            corrArea += e.correlation() * w;
            area += w;
        }
        double corr = area > 0 ? corrArea / area : 0.0;

        double score;
        if (cross) {
            score = 0.40 + 0.45 * Math.max(0.0, corr)
                    - 0.15 * Math.min(1.0, gapMs / 500.0)
                    - 0.20 * satFrac
                    - 0.15 * extrapFrac
                    - 0.15 * Math.min(1.0, clockMs / 100.0);
        } else {
            score = 0.90
                    - 0.20 * satFrac
                    - 0.15 * extrapFrac
                    - 0.05 * Math.min(1.0, gapMs / 500.0);
        }
        score = clamp01(score);
        return new StitchedEvent(
                Fingerprints.eventFingerprint(ids),
                ids, devices, cross,
                startMs, endMs, startHz, endHz,
                score, corr, Math.max(0.0, gapMs), satFrac, extrapFrac, clockMs,
                energy, edges, competing);
    }

    private static void gatherMemberEdges(List<CorrectedSegment> members,
                                          Map<Integer, List<LinkEdge>> source,
                                          List<LinkEdge> sink) {
        java.util.Set<String> memberIds = new java.util.HashSet<>();
        for (CorrectedSegment m : members) {
            memberIds.add(m.id());
        }
        for (List<LinkEdge> list : source.values()) {
            for (LinkEdge e : list) {
                if (memberIds.contains(e.segmentA()) && memberIds.contains(e.segmentB())
                        && !sink.contains(e)) {
                    sink.add(e);
                }
            }
        }
    }

    /** Union length (half-open, abutment counts as zero gap) of member time spans. */
    private static double coveredTime(List<CorrectedSegment> members) {
        double[][] spans = members.stream()
                .map(s -> new double[]{s.cTStartMs(), s.cTEndMs()})
                .sorted(Comparator.comparingDouble(a -> a[0]))
                .toArray(double[][]::new);
        double covered = 0.0;
        double curStart = spans[0][0];
        double curEnd = spans[0][1];
        for (int i = 1; i < spans.length; i++) {
            if (spans[i][0] > curEnd) {
                covered += curEnd - curStart;
                curStart = spans[i][0];
                curEnd = spans[i][1];
            } else {
                curEnd = Math.max(curEnd, spans[i][1]);
            }
        }
        return covered + curEnd - curStart;
    }

    private static int find(int[] parent, int x) {
        int r = x;
        while (parent[r] != r) {
            r = parent[r];
        }
        while (parent[x] != r) {
            int next = parent[x];
            parent[x] = r;
            x = next;
        }
        return r;
    }

    private static int union(int[] parent, int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        parent[hi] = lo;
        return lo;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }
}
