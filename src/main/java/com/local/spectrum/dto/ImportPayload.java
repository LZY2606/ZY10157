package com.local.spectrum.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportPayload(String batchId, List<SegmentPayload> segments) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SegmentPayload(
      String deviceId,
      Long startNanos,
      Long endNanos,
      Long centerFrequencyHz,
      Long resolutionBandwidthHz,
      List<Double> powerBuckets,
      Double deviceTemperatureC,
      String calibrationVersion) {
  }
}
