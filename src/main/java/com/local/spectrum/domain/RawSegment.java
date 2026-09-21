package com.local.spectrum.domain;

public record RawSegment(
    String id,
    String batchId,
    String deviceId,
    long startNanos,
    long endNanos,
    long centerFrequencyHz,
    long resolutionBandwidthHz,
    int bucketCount,
    byte[] powerBucketsGz,
    double deviceTemperatureC,
    String calibrationVersion,
    String contentHash) {
}
