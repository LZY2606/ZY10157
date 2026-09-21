package gsb.domain;

/**
 * A cross-segment link candidate whose evidence is the real intersection of
 * corrected half-open sweep windows (never centre-frequency proximity).
 */
public record LinkEdge(
        String edgeId,
        String segmentA,
        String segmentB,
        String deviceA,
        String deviceB,
        boolean crossDevice,
        double overlapStartHz,
        double overlapEndHz,
        double overlapStartMs,
        double overlapEndMs,
        double overlapHz,
        double overlapMs,
        double energyA,
        double energyB,
        double correlation,
        double clockUncertaintyMs,
        double score,
        String reason) {

    public static String idOf(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
