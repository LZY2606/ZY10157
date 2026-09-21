package gsb.domain;

/**
 * Half-open interval arithmetic. All frequency (Hz) and time (ms since the
 * batch-local epoch) ranges are [start, end): two abutting ranges share no
 * point, so their energy is never counted twice.
 */
public final class Intervals {

    private Intervals() {
    }

    /** Intersection length of two half-open intervals; zero when merely touching. */
    public static double overlap(double s1, double e1, double s2, double e2) {
        double lo = Math.max(s1, s2);
        double hi = Math.min(e1, e2);
        return hi > lo ? hi - lo : 0.0;
    }

    public static boolean overlaps(double s1, double e1, double s2, double e2) {
        return Math.max(s1, s2) < Math.min(e1, e2);
    }

    public static double gap(double s1, double e1, double s2, double e2) {
        if (overlaps(s1, e1, s2, e2)) {
            return 0.0;
        }
        return e1 <= s2 ? s2 - e1 : s1 - e2;
    }
}
