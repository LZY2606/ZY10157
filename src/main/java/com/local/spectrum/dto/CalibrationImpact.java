package com.local.spectrum.dto;

import java.util.List;

public record CalibrationImpact(
    String calibrationVersion,
    List<String> affectedEventFingerprints,
    List<String> addedEventFingerprints,
    List<String> removedEventFingerprints,
    List<EventSummary> affectedEvents) {

  public record EventSummary(
      String id,
      String alternateId,
      long startNanos,
      long endNanos,
      double frequencyLowHz,
      double frequencyHighHz,
      String reason) {
  }
}
