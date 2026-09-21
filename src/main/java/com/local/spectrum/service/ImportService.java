package com.local.spectrum.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.spectrum.domain.RawSegment;
import com.local.spectrum.dto.ImportPayload;
import com.local.spectrum.repository.SpectrumRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportService {
  private final ObjectMapper objectMapper;
  private final SpectrumRepository repository;

  public ImportService(ObjectMapper objectMapper, SpectrumRepository repository) {
    this.objectMapper = objectMapper;
    this.repository = repository;
  }

  @Transactional
  public ImportResult importPayload(byte[] body, boolean gzipped) {
    byte[] jsonBytes = gzipped ? gunzip(body) : body;
    ImportPayload payload;
    try {
      payload = objectMapper.readValue(jsonBytes, ImportPayload.class);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Invalid import JSON", exception);
    }
    validate(payload);
    String batchId = payload.batchId();
    String sourceHash = Hashes.sha256Bytes("src_", jsonBytes);
    var existing = repository.findBatch(batchId);
    if (existing.isPresent()) {
      return new ImportResult(batchId, existing.get().segmentCount(), 0, 0, true,
          existing.get().sourceHash());
    }

    int inserted = 0;
    int duplicate = 0;
    for (ImportPayload.SegmentPayload segmentPayload : payload.segments()) {
      byte[] bucketsJson = writeJson(segmentPayload.powerBuckets());
      byte[] bucketsGz = gzip(bucketsJson);
      String contentHash = Hashes.sha256Bytes("raw_", contentBytes(segmentPayload, bucketsJson));
      String id = contentHash;
      if (repository.segmentExists(id)) {
        duplicate++;
        continue;
      }
      RawSegment segment = new RawSegment(
          id,
          batchId,
          segmentPayload.deviceId(),
          segmentPayload.startNanos(),
          segmentPayload.endNanos(),
          segmentPayload.centerFrequencyHz(),
          segmentPayload.resolutionBandwidthHz(),
          segmentPayload.powerBuckets().size(),
          bucketsGz,
          segmentPayload.deviceTemperatureC(),
          segmentPayload.calibrationVersion(),
          contentHash);
      repository.insertSegment(segment);
      inserted++;
    }
    repository.insertBatchIfMissing(batchId, sourceHash, inserted + duplicate,
        Instant.now().toEpochMilli());
    return new ImportResult(batchId, payload.segments().size(), inserted, duplicate, false, sourceHash);
  }

  private void validate(ImportPayload payload) {
    List<String> errors = new ArrayList<>();
    if (payload == null || isBlank(payload.batchId())) {
      errors.add("batchId is required");
    }
    if (payload == null || payload.segments() == null || payload.segments().isEmpty()) {
      errors.add("segments are required");
      return;
    }
    for (int index = 0; index < payload.segments().size(); index++) {
      ImportPayload.SegmentPayload segment = payload.segments().get(index);
      if (isBlank(segment.deviceId())) errors.add("segments[" + index + "].deviceId required");
      if (segment.startNanos() == null || segment.endNanos() == null
          || segment.startNanos() >= segment.endNanos()) {
        errors.add("segments[" + index + "] must use a half-open startNanos < endNanos");
      }
      if (segment.centerFrequencyHz() == null) {
        errors.add("segments[" + index + "].centerFrequencyHz required");
      }
      if (segment.resolutionBandwidthHz() == null || segment.resolutionBandwidthHz() <= 0) {
        errors.add("segments[" + index + "].resolutionBandwidthHz must be positive");
      }
      if (segment.powerBuckets() == null || segment.powerBuckets().isEmpty()) {
        errors.add("segments[" + index + "].powerBuckets required");
      }
      if (segment.deviceTemperatureC() == null) {
        errors.add("segments[" + index + "].deviceTemperatureC required");
      }
      if (isBlank(segment.calibrationVersion())) {
        errors.add("segments[" + index + "].calibrationVersion required");
      }
    }
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", errors));
    }
  }

  private byte[] contentBytes(ImportPayload.SegmentPayload segment, byte[] bucketsJson) {
    String canonical = String.join("|",
        segment.deviceId(),
        segment.startNanos().toString(),
        segment.endNanos().toString(),
        segment.centerFrequencyHz().toString(),
        segment.resolutionBandwidthHz().toString(),
        segment.deviceTemperatureC().toString(),
        segment.calibrationVersion(),
        HexFormat.of().formatHex(Hashes.sha256Bytes("", bucketsJson).getBytes(StandardCharsets.UTF_8)));
    return canonical.getBytes(StandardCharsets.UTF_8);
  }

  private byte[] writeJson(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Cannot serialize power buckets", exception);
    }
  }

  private static byte[] gzip(byte[] value) {
    try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(value);
      gzip.close();
      return out.toByteArray();
    } catch (IOException exception) {
      throw new IllegalArgumentException("Cannot gzip power buckets", exception);
    }
  }

  private static byte[] gunzip(byte[] value) {
    try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(value));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      input.transferTo(out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw new IllegalArgumentException("Invalid gzip import", exception);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public record ImportResult(
      String batchId,
      int segmentCount,
      int insertedSegments,
      int duplicateSegments,
      boolean idempotentReplay,
      String sourceHash) {
  }
}
