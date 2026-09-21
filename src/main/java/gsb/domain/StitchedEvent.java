package gsb.domain;

import java.util.List;

/**
 * A stitched interference event. {@code fingerprint} is a stable hash of the
 * sorted member segment ids; {@code score} is decomposed into independent
 * uncertainty contributions. {@code competing} edges are lower-scored
 * alternatives between the same groups, kept as evidence rather than deleted.
 */
public record StitchedEvent(
        String fingerprint,
        List<String> segmentIds,
        List<String> devices,
        boolean crossDevice,
        double startMs,
        double endMs,
        double startHz,
        double endHz,
        double score,
        double linkCorrelation,
        double gapMs,
        double saturatedFraction,
        double extrapolatedFraction,
        double clockUncertaintyMs,
        double evidenceEnergyMwHzMs,
        List<LinkEdge> edges,
        List<LinkEdge> competing) {
}
