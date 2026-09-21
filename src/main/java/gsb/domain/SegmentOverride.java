package gsb.domain;

/** Per-segment fine tuning ("segment frequency offset"), versioned with the correction family. */
public record SegmentOverride(
        String segmentId,
        double loOffsetHz,
        double clockOffsetMs) {
}
