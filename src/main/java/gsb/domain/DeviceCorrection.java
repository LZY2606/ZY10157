package gsb.domain;

/**
 * Versioned per-device correction model. Time correction is
 * {@code tCorr = tRaw * (1 + clockPpm/1e6) + clockOffsetMs}; frequency is
 * {@code fCorr = fRaw - loOffsetHz}; power is {@code pCorr = pRaw + gainDb}.
 * The model only applies by interpolation while device temperature lies in
 * [validFromTempC, validToTempC]; outside it, segments are flagged as
 * extrapolated (a distinct uncertainty source).
 */
public record DeviceCorrection(
        String deviceId,
        double clockPpm,
        double clockOffsetMs,
        double loOffsetHz,
        double gainDb,
        double validFromTempC,
        double validToTempC,
        String calibrationVersion,
        long version) {
}
