package gsb.domain;

import java.util.List;

/**
 * A compressed capture fragment exported from one sweep device.
 * {@code buckets} are linear-space mean power (dBm) over
 * {@code binHz}-wide half-open bins spanning [fStartHz, fStartHz + n*binHz).
 * Time range is [tStartMs, tEndMs). All fields are immutable input evidence.
 */
public record RawSegment(
        String segmentId,
        String batchId,
        String deviceId,
        double tStartMs,
        double tEndMs,
        double fStartHz,
        double binHz,
        List<Double> buckets,
        double temperatureC,
        String calibrationVersion,
        double clockDriftPpm,
        double loOffsetHz,
        boolean saturated) {

    public double fEndHz() {
        return fStartHz + binHz * buckets.size();
    }

    public double durationMs() {
        return tEndMs - tStartMs;
    }
}
