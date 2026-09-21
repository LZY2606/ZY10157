package com.local.spectrum.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.spectrum.domain.CorrectionModel;
import com.local.spectrum.repository.SpectrumRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CorrectionService {
  static final CorrectionModel DEFAULT = new CorrectionModel(
      0, 0L, true, "identity correction", List.of());

  private final SpectrumRepository repository;
  private final ObjectMapper objectMapper;

  public CorrectionService(SpectrumRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  public CorrectionModel active() {
    return repository.activeCorrection().orElse(DEFAULT);
  }

  @Transactional
  public CorrectionModel replaceModel(CorrectionModel requested) {
    List<CorrectionModel.Entry> entries = requested.entries() == null
        ? List.of()
        : requested.entries().stream()
            .sorted(Comparator.comparing(CorrectionModel.Entry::deviceId)
                .thenComparing(CorrectionModel.Entry::calibrationVersion,
                    Comparator.nullsLast(String::compareTo)))
            .toList();
    CorrectionModel normalized = new CorrectionModel(
        null, System.currentTimeMillis(), true,
        requested.note() == null || requested.note().isBlank() ? "correction" : requested.note(),
        entries);
    return repository.saveCorrection(normalized, writeJson(normalized));
  }

  @Transactional
  public CorrectionModel adjustDeviceOffset(String deviceId, String calibrationVersion,
      double offsetHz, String note) {
    CorrectionModel current = active();
    List<CorrectionModel.Entry> nextEntries = new java.util.ArrayList<>(current.entries());
    nextEntries.removeIf(entry -> entry.deviceId().equals(deviceId)
        && calibrationVersionsMatch(entry.calibrationVersion(), calibrationVersion));
    nextEntries.add(new CorrectionModel.Entry(
        deviceId,
        calibrationVersion,
        offsetHz,
        0.0d,
        0.0d,
        0.0d,
        0.0d,
        0.0d,
        0.0d,
        null,
        null,
        null,
        null,
        false,
        note == null ? "manual segment frequency offset" : note));
    CorrectionModel next = new CorrectionModel(null, System.currentTimeMillis(), true,
        "device offset adjustment", nextEntries.stream()
            .sorted(Comparator.comparing(CorrectionModel.Entry::deviceId)
                .thenComparing(CorrectionModel.Entry::calibrationVersion,
                    Comparator.nullsLast(String::compareTo)))
            .toList());
    return repository.saveCorrection(next, writeJson(next));
  }

  public AppliedCorrection apply(CorrectionModel model, String deviceId, String calibrationVersion,
      double rawFrequencyHz, long rawNanos, double temperatureC) {
    CorrectionModel.Entry best = null;
    for (CorrectionModel.Entry entry : model.entries()) {
      if (!entry.deviceId().equals(deviceId)
          || !calibrationVersionsMatch(entry.calibrationVersion(), calibrationVersion)) {
        continue;
      }
      if (best == null || specificity(entry) > specificity(best)) {
        best = entry;
      }
    }
    if (best == null) {
      return new AppliedCorrection(rawFrequencyHz, rawNanos, false, false);
    }
    double relativeHz = rawFrequencyHz - centerOf(best);
    double temperatureHz = best.temperatureCoefficientHzPerC()
        * (temperatureC + best.temperatureOffsetC() - best.referenceTemperatureC());
    double correctedFrequency = rawFrequencyHz + best.frequencyOffsetHz()
        + best.frequencySlope() * relativeHz + temperatureHz;
    double clockShift = best.clockOffsetNanos()
        + rawNanos * best.clockRatePpb() / 1_000_000_000.0d;
    long correctedNanos = rawNanos + Math.round(clockShift);
    boolean extrapolated = outsideFrequencyDomain(best, rawFrequencyHz)
        || outsideTimeDomain(best, rawNanos);
    return new AppliedCorrection(correctedFrequency, correctedNanos, extrapolated,
        best.clockUnidentifiable());
  }

  private static int specificity(CorrectionModel.Entry entry) {
    int score = 1;
    if (entry.calibrationVersion() != null && !entry.calibrationVersion().isBlank()) score++;
    if (entry.domainStartHz() != null || entry.domainEndHz() != null) score++;
    if (entry.validFromNanos() != null || entry.validToNanos() != null) score++;
    return score;
  }

  private static boolean calibrationVersionsMatch(String entryVersion, String segmentVersion) {
    return entryVersion == null || entryVersion.isBlank() || entryVersion.equals(segmentVersion);
  }

  private static double centerOf(CorrectionModel.Entry entry) {
    if (entry.domainStartHz() != null && entry.domainEndHz() != null) {
      return (entry.domainStartHz() + entry.domainEndHz()) / 2.0d;
    }
    return 0.0d;
  }

  private static boolean outsideFrequencyDomain(CorrectionModel.Entry entry, double frequencyHz) {
    return (entry.domainStartHz() != null && frequencyHz < entry.domainStartHz())
        || (entry.domainEndHz() != null && frequencyHz > entry.domainEndHz());
  }

  private static boolean outsideTimeDomain(CorrectionModel.Entry entry, long nanos) {
    return (entry.validFromNanos() != null && nanos < entry.validFromNanos())
        || (entry.validToNanos() != null && nanos > entry.validToNanos());
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Cannot serialize correction model", exception);
    }
  }

  public record AppliedCorrection(
      double frequencyHz,
      long nanos,
      boolean extrapolated,
      boolean clockUnidentifiable) {
  }
}
