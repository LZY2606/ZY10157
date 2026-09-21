package com.local.spectrum.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.spectrum.domain.Edge;
import com.local.spectrum.domain.Observation;
import java.util.List;
import org.junit.jupiter.api.Test;

class StitchingEngineTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void requiresTrueOverlapAcrossSweepBoundary() {
    Observation left = observation("left", "a", 0, 100, 90, 100, -65);
    Observation touching = observation("touching", "b", 50, 150, 100, 110, -65);
    Observation overlapping = observation("overlapping", "b", 50, 150, 99, 110, -65);

    assertThat(StitchingEngine.edges(List.of(left, touching), objectMapper)).isEmpty();
    List<Edge> edges = StitchingEngine.edges(List.of(left, overlapping), objectMapper);
    assertThat(edges).hasSize(1);
    assertThat(edges.get(0).frequencyOverlapHz()).isEqualTo(1.0d);
    assertThat(edges.get(0).reason()).contains("true frequency overlap");
  }

  @Test
  void reportsIndependentUncertaintyContributors() {
    Observation left = observation("saturated", "a", 0, 100, 90, 110, -18);
    Observation right = observation("clock", "b", 101, 150, 100, 110, -65);

    Edge edge = StitchingEngine.edges(List.of(left, right), objectMapper).get(0);

    assertThat(edge.uncertainty().gapNanos()).isEqualTo(1L);
    assertThat(edge.uncertainty().saturatedBucketFraction()).isEqualTo(0.5d);
    assertThat(edge.uncertainty().extrapolatedBucketFraction()).isZero();
    assertThat(edge.uncertainty().clockUnidentifiableFraction()).isEqualTo(1.0d);
    assertThat(edge.scoreContributions()).containsKeys("frequencyOverlap",
        "timeContinuity", "profileAgreement", "uncertaintyPenalty");
  }

  private Observation observation(String id, String device, long start, long end,
      double low, double high, double dbm) {
    return new Observation(
        id, "segment-" + id, "batch", device, start, end, start, end, low, high, low, high,
        10L, List.of(new Observation.BucketPower(low, high, dbm)), 25.0d,
        "cal", 0, dbm >= -20.0d, false, "b".equals(device));
  }
}
