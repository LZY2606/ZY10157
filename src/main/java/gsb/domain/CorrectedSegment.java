package gsb.domain;

import java.util.List;

/**
 * A raw segment after applying a versioned per-device correction model.
 * Frequency/coordinate fields are in the common reference frame;
 * {@code calibratedBuckets} are gain-corrected power (dBm) used for energy and
 * correlation. Raw bucket values stay in the segments table and are never
 * overwritten.
 */
public record CorrectedSegment(
        RawSegment raw,
        double cTStartMs,
        double cTEndMs,
        double cFStartHz,
        double cFEndHz,
        double cBinHz,
        double cClockDriftPpm,
        double cLoOffsetHz,
        double cGainDb,
        boolean extrapolated,
        List<Double> calibratedBuckets) {

    public String id() {
        return raw.segmentId();
    }

    public String device() {
        return raw.deviceId();
    }

    /** Linear-space calibrated power integrated over a half-open frequency slice. */
    public double energy(double qStartHz, double qEndHz) {
        double qs = Math.max(qStartHz, cFStartHz);
        double qe = Math.min(qEndHz, cFEndHz);
        if (qe <= qs) {
            return 0.0;
        }
        int i0 = Math.max(0, (int) Math.floor((qs - cFStartHz) / cBinHz));
        int i1 = Math.min(calibratedBuckets.size(),
                (int) Math.ceil((qe - cFStartHz) / cBinHz));
        double energy = 0.0;
        for (int i = i0; i < i1; i++) {
            double bLo = cFStartHz + i * cBinHz;
            double bHi = bLo + cBinHz;
            double w = Intervals.overlap(bLo, bHi, qs, qe);
            energy += Math.pow(10.0, calibratedBuckets.get(i) / 10.0) * w;
        }
        return energy;
    }

    /** Mean calibrated linear power over [qStartHz, qEndHz); zero outside coverage. */
    public double meanPower(double qStartHz, double qEndHz) {
        double w = Intervals.overlap(cFStartHz, cFEndHz, qStartHz, qEndHz);
        return w > 0 ? energy(qStartHz, qEndHz) / w : 0.0;
    }
}
