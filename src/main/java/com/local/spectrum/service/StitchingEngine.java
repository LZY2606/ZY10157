package com.local.spectrum.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.spectrum.domain.Edge;
import com.local.spectrum.domain.Observation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class StitchingEngine {
  static final long MAX_GAP_NANOS = 200_000_000L;
  static final double STRONG_SCORE = 0.72d;
  static final double ALTERNATIVE_SCORE = 0.45d;

  private StitchingEngine() {
  }

  static List<Edge> edges(List<Observation> observations, ObjectMapper objectMapper) {
    List<Observation> sorted = observations.stream()
        .sorted(Comparator.comparing(Observation::id))
        .toList();
    List<Edge> edges = new ArrayList<>();
    for (int leftIndex = 0; leftIndex < sorted.size(); leftIndex++) {
      for (int rightIndex = leftIndex + 1; rightIndex < sorted.size(); rightIndex++) {
        Observation left = sorted.get(leftIndex);
        Observation right = sorted.get(rightIndex);
        Edge edge = edge(left, right, objectMapper);
        if (edge != null) {
          edges.add(edge);
        }
      }
    }
    edges.sort(Comparator.comparing(Edge::key));
    return edges;
  }

  private static Edge edge(Observation left, Observation right, ObjectMapper objectMapper) {
    double frequencyOverlap = Geometry.overlap(
        left.frequencyLowHz(), left.frequencyHighHz(),
        right.frequencyLowHz(), right.frequencyHighHz());
    if (frequencyOverlap <= 1.0e-6d) {
      return null;
    }
    long timeGap = Geometry.gap(
        left.startNanos(), left.endNanos(),
        right.startNanos(), right.endNanos());
    if (timeGap > MAX_GAP_NANOS) {
      return null;
    }
    long timeOverlap = Geometry.overlap(
        left.startNanos(), left.endNanos(),
        right.startNanos(), right.endNanos());

    double leftFrequencyWidth = left.frequencyHighHz() - left.frequencyLowHz();
    double rightFrequencyWidth = right.frequencyHighHz() - right.frequencyLowHz();
    double frequencyScore = clamp01(frequencyOverlap / Math.min(leftFrequencyWidth, rightFrequencyWidth));

    double leftDuration = left.endNanos() - left.startNanos();
    double rightDuration = right.endNanos() - right.startNanos();
    double timeScore;
    if (timeOverlap > 0) {
      timeScore = clamp01(timeOverlap / Math.min(leftDuration, rightDuration));
    } else {
      timeScore = clamp01(1.0d - (double) timeGap / MAX_GAP_NANOS);
    }

    double profileScore = profileScore(left, right, frequencyOverlap);
    double score = 0.40d * frequencyScore + 0.35d * timeScore + 0.25d * profileScore;
    boolean crossDevice = !left.deviceId().equals(right.deviceId());
    Edge.Uncertainty uncertainty = uncertainty(left, right, timeGap, crossDevice);
    String key = Hashes.canonicalKey("edge_", left.id(), right.id());
    String status = score >= STRONG_SCORE
        ? Edge.Status.STRONG.name()
        : score >= ALTERNATIVE_SCORE ? Edge.Status.ALTERNATIVE.name() : Edge.Status.BELOW_THRESHOLD.name();
    Map<String, Double> contributions = new LinkedHashMap<>();
    contributions.put("frequencyOverlap", round(frequencyScore));
    contributions.put("timeContinuity", round(timeScore));
    contributions.put("profileAgreement", round(profileScore));
    contributions.put("uncertaintyPenalty", round(uncertaintyPenalty(uncertainty)));

    String reason = String.format(Locale.ROOT,
        "true frequency overlap %.3f Hz and time gap %,d ns; center closeness is not used",
        frequencyOverlap, timeGap);
    return new Edge(
        key,
        left.id(),
        right.id(),
        left.deviceId(),
        right.deviceId(),
        crossDevice,
        round(frequencyOverlap),
        round(timeOverlap),
        timeGap,
        round(score),
        contributions,
        uncertainty,
        status,
        reason);
  }

  private static double profileScore(Observation left, Observation right, double overlapBandwidth) {
    double weighted = 0.0d;
    double totalWeight = 0.0d;
    for (Observation.BucketPower leftBucket : left.buckets()) {
      for (Observation.BucketPower rightBucket : right.buckets()) {
        double overlap = Geometry.overlap(
            leftBucket.lowHz(), leftBucket.highHz(),
            rightBucket.lowHz(), rightBucket.highHz());
        if (overlap <= 1.0e-6d) {
          continue;
        }
        double normalizedLeft = normalizeDbm(leftBucket.dbm());
        double normalizedRight = normalizeDbm(rightBucket.dbm());
        weighted += overlap * (1.0d - Math.abs(normalizedLeft - normalizedRight));
        totalWeight += overlap;
      }
    }
    if (totalWeight <= 1.0e-9d) {
      return 0.50d;
    }
    return clamp01(weighted / totalWeight);
  }

  private static Edge.Uncertainty uncertainty(Observation left, Observation right,
      long gap, boolean crossDevice) {
    double saturated = (saturatedFraction(left) + saturatedFraction(right)) / 2.0d;
    double extrapolated = (extrapolatedFraction(left) + extrapolatedFraction(right)) / 2.0d;
    double clockUnidentifiable = crossDevice
        && (left.clockUnidentifiable() || right.clockUnidentifiable()) ? 1.0d : 0.0d;
    return new Edge.Uncertainty(
        gap,
        round(saturated),
        round(extrapolated),
        round(clockUnidentifiable));
  }

  static double uncertaintyPenalty(Edge.Uncertainty uncertainty) {
    double gapPart = clamp01((double) uncertainty.gapNanos() / MAX_GAP_NANOS) * 0.25d;
    return round(gapPart
        + uncertainty.saturatedBucketFraction() * 0.25d
        + uncertainty.extrapolatedBucketFraction() * 0.25d
        + uncertainty.clockUnidentifiableFraction() * 0.25d);
  }

  private static double saturatedFraction(Observation observation) {
    long count = observation.buckets().stream()
        .filter(bucket -> bucket.dbm() >= ObservationService.SATURATED_DBM)
        .count();
    return (double) count / observation.buckets().size();
  }

  private static double extrapolatedFraction(Observation observation) {
    return observation.extrapolated() ? 1.0d : 0.0d;
  }

  private static double normalizeDbm(double dbm) {
    return clamp01((dbm + 100.0d) / 80.0d);
  }

  private static double clamp01(double value) {
    return Math.max(0.0d, Math.min(1.0d, value));
  }

  static double round(double value) {
    return Math.round(value * 1_000_000.0d) / 1_000_000.0d;
  }
}
