package com.local.spectrum.domain;

import java.util.List;

public record CorrectionModel(
    Integer version,
    long createdAt,
    boolean active,
    String note,
    List<Entry> entries) {

  public CorrectionModel withVersion(int nextVersion, boolean nextActive) {
    return new CorrectionModel(nextVersion, createdAt, nextActive, note, entries);
  }

  public record Entry(
      String deviceId,
      String calibrationVersion,
      double frequencyOffsetHz,
      double frequencySlope,
      double clockOffsetNanos,
      double clockRatePpb,
      double temperatureOffsetC,
      double temperatureCoefficientHzPerC,
      double referenceTemperatureC,
      Double domainStartHz,
      Double domainEndHz,
      Long validFromNanos,
      Long validToNanos,
      boolean clockUnidentifiable,
      String note) {
  }
}
