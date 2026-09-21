package com.local.spectrum.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.spectrum.domain.CorrectionModel;
import com.local.spectrum.domain.Observation;
import com.local.spectrum.domain.RawSegment;
import com.local.spectrum.repository.SpectrumRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Service;

@Service
public class ObservationService {
  public static final double ACTIVE_THRESHOLD_DBM = -85.0d;
  public static final double SATURATED_DBM = -20.0d;

  private final SpectrumRepository repository;
  private final CorrectionService correctionService;
  private final ObjectMapper objectMapper;

  public ObservationService(SpectrumRepository repository,
      CorrectionService correctionService, ObjectMapper objectMapper) {
    this.repository = repository;
    this.correctionService = correctionService;
    this.objectMapper = objectMapper;
  }

  public List<Observation> extract(CorrectionModel model) {
    List<Observation> observations = new ArrayList<>();
    for (RawSegment segment : repository.listSegments()) {
      observations.addAll(extractSegment(segment, model));
    }
    observations.sort(java.util.Comparator.comparing(Observation::startNanos)
        .thenComparing(Observation::frequencyLowHz)
        .thenComparing(Observation::id));
    return observations;
  }

  private List<Observation> extractSegment(RawSegment segment, CorrectionModel model) {
    List<Double> powers = readBuckets(segment.powerBucketsGz());
    List<Observation> result = new ArrayList<>();
    int runStart = -1;
    for (int index = 0; index <= powers.size(); index++) {
      boolean active = index < powers.size() && powers.get(index) >= ACTIVE_THRESHOLD_DBM;
      if (active && runStart < 0) {
        runStart = index;
      }
      if (!active && runStart >= 0) {
        result.add(buildObservation(segment, model, powers, runStart, index - 1));
        runStart = -1;
      }
    }
    return result;
  }

  private Observation buildObservation(RawSegment segment, CorrectionModel model,
      List<Double> powers, int firstBucket, int lastBucket) {
    long bucketBandwidth = segment.resolutionBandwidthHz();
    long span = bucketBandwidth * segment.bucketCount();
    double rawStartFrequency = segment.centerFrequencyHz() - span / 2.0d;

    double rawLow = rawStartFrequency + (long) firstBucket * bucketBandwidth;
    double rawHigh = rawStartFrequency + (long) (lastBucket + 1) * bucketBandwidth;
    CorrectionService.AppliedCorrection low = correctionService.apply(
        model, segment.deviceId(), segment.calibrationVersion(), rawLow,
        segment.startNanos(), segment.deviceTemperatureC());
    CorrectionService.AppliedCorrection high = correctionService.apply(
        model, segment.deviceId(), segment.calibrationVersion(), rawHigh,
        segment.endNanos(), segment.deviceTemperatureC());

    List<Observation.BucketPower> buckets = new ArrayList<>();
    int saturated = 0;
    int extrapolated = 0;
    for (int index = firstBucket; index <= lastBucket; index++) {
      double bucketRawLow = rawStartFrequency + (long) index * bucketBandwidth;
      double bucketRawHigh = bucketRawLow + bucketBandwidth;
      CorrectionService.AppliedCorrection bucketLow = correctionService.apply(
          model, segment.deviceId(), segment.calibrationVersion(), bucketRawLow,
          segment.startNanos(), segment.deviceTemperatureC());
      CorrectionService.AppliedCorrection bucketHigh = correctionService.apply(
          model, segment.deviceId(), segment.calibrationVersion(), bucketRawHigh,
          segment.endNanos(), segment.deviceTemperatureC());
      double power = powers.get(index);
      if (power >= SATURATED_DBM) saturated++;
      if (bucketLow.extrapolated() || bucketHigh.extrapolated()) extrapolated++;
      buckets.add(new Observation.BucketPower(
          Math.min(bucketLow.frequencyHz(), bucketHigh.frequencyHz()),
          Math.max(bucketLow.frequencyHz(), bucketHigh.frequencyHz()),
          power));
    }

    String deterministicBody = String.join("|",
        segment.id(), String.valueOf(firstBucket), String.valueOf(lastBucket),
        String.valueOf(model.version()));
    String id = Hashes.sha256Bytes("obs_", deterministicBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    int bucketCount = lastBucket - firstBucket + 1;
    return new Observation(
        id,
        segment.id(),
        segment.batchId(),
        segment.deviceId(),
        low.nanos(),
        high.nanos(),
        segment.startNanos(),
        segment.endNanos(),
        Math.min(low.frequencyHz(), high.frequencyHz()),
        Math.max(low.frequencyHz(), high.frequencyHz()),
        rawLow,
        rawHigh,
        bucketBandwidth,
        buckets,
        segment.deviceTemperatureC(),
        segment.calibrationVersion(),
        model.version(),
        saturated > 0,
        extrapolated > 0,
        low.clockUnidentifiable() || high.clockUnidentifiable());
  }

  private List<Double> readBuckets(byte[] gzipBytes) {
    try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(gzipBytes))) {
      return objectMapper.readValue(input, new TypeReference<List<Double>>() {
      });
    } catch (IOException exception) {
      throw new IllegalStateException("Stored power buckets are invalid", exception);
    }
  }
}
