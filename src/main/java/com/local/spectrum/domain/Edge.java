package com.local.spectrum.domain;

import java.util.Map;

public record Edge(
    String key,
    String observationIdA,
    String observationIdB,
    String deviceIdA,
    String deviceIdB,
    boolean crossDevice,
    double frequencyOverlapHz,
    double timeOverlapNanos,
    long timeGapNanos,
    double score,
    Map<String, Double> scoreContributions,
    Uncertainty uncertainty,
    String status,
    String reason) {

  public enum Status {
    STRONG,
    ALTERNATIVE,
    REJECTED,
    FORCED,
    BELOW_THRESHOLD
  }

  public record Uncertainty(
      long gapNanos,
      double saturatedBucketFraction,
      double extrapolatedBucketFraction,
      double clockUnidentifiableFraction) {
  }
}
