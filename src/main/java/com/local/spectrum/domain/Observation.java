package com.local.spectrum.domain;

import java.util.List;

public record Observation(
    String id,
    String segmentId,
    String batchId,
    String deviceId,
    long startNanos,
    long endNanos,
    long rawStartNanos,
    long rawEndNanos,
    double frequencyLowHz,
    double frequencyHighHz,
    double rawFrequencyLowHz,
    double rawFrequencyHighHz,
    long resolutionBandwidthHz,
    List<BucketPower> buckets,
    double deviceTemperatureC,
    String calibrationVersion,
    int correctionVersion,
    boolean saturated,
    boolean extrapolated,
    boolean clockUnidentifiable) {

  public record BucketPower(double lowHz, double highHz, double dbm) {
  }
}
