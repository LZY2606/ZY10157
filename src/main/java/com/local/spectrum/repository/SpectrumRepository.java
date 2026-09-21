package com.local.spectrum.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.spectrum.domain.AutoLinks;
import com.local.spectrum.domain.CorrectionModel;
import com.local.spectrum.domain.ManualDecisions;
import com.local.spectrum.domain.RawSegment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SpectrumRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public SpectrumRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public void insertBatchIfMissing(String batchId, String sourceHash, int segmentCount, long importedAt) {
    jdbc.update("""
        INSERT INTO import_batch(id, imported_at, source_hash, segment_count)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(id) DO NOTHING
        """, batchId, importedAt, sourceHash, segmentCount);
  }

  public Optional<ImportBatchRecord> findBatch(String batchId) {
    return jdbc.query("SELECT id, source_hash, segment_count FROM import_batch WHERE id = ?",
        (rs, rowNum) -> new ImportBatchRecord(rs.getString("id"), rs.getString("source_hash"),
            rs.getInt("segment_count")), batchId).stream().findFirst();
  }

  @Transactional
  public void insertSegment(RawSegment segment) {
    jdbc.update("""
        INSERT INTO raw_segment(
          id, batch_id, device_id, start_nanos, end_nanos, center_frequency_hz,
          resolution_bandwidth_hz, bucket_count, power_buckets_gz, device_temperature_c,
          calibration_version, content_hash)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO NOTHING
        """, segment.id(), segment.batchId(), segment.deviceId(), segment.startNanos(),
        segment.endNanos(), segment.centerFrequencyHz(), segment.resolutionBandwidthHz(),
        segment.bucketCount(), segment.powerBucketsGz(), segment.deviceTemperatureC(),
        segment.calibrationVersion(), segment.contentHash());
  }

  public boolean segmentExists(String id) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM raw_segment WHERE id = ?",
        Integer.class, id);
    return count != null && count > 0;
  }

  public List<RawSegment> listSegments() {
    return jdbc.query("SELECT * FROM raw_segment ORDER BY device_id, start_nanos, id",
        this::mapSegment);
  }

  @Transactional
  public CorrectionModel saveCorrection(CorrectionModel model, String body) {
    long now = System.currentTimeMillis();
    jdbc.update("UPDATE correction_model SET active = 0 WHERE active = 1");
    jdbc.update("""
        INSERT INTO correction_model(created_at, active, note, body_json)
        VALUES (?, 1, ?, ?)
        """, now, model.note(), body);
    Integer version = jdbc.queryForObject("SELECT last_insert_rowid()", Integer.class);
    CorrectionModel saved = model.withVersion(version, true);
    jdbc.update("UPDATE correction_model SET body_json = ? WHERE version = ?",
        writeJson(saved), version);
    return saved;
  }

  public Optional<CorrectionModel> activeCorrection() {
    return jdbc.query("SELECT body_json FROM correction_model WHERE active = 1 ORDER BY version DESC LIMIT 1",
        (rs, rowNum) -> read(rs.getString("body_json"), CorrectionModel.class)).stream().findFirst();
  }

  @Transactional
  public AutoLinks saveAutoLinks(String body, int modelVersion, String note) {
    long now = System.currentTimeMillis();
    jdbc.update("""
        INSERT INTO auto_link_version(created_at, model_version, note, body_json)
        VALUES (?, ?, ?, ?)
        """, now, modelVersion, note, body);
    Integer version = jdbc.queryForObject("SELECT last_insert_rowid()", Integer.class);
    AutoLinks saved = new AutoLinks(version, now, modelVersion, note,
        read(body, AutoLinks.class).strongEdgeKeys());
    String savedBody = writeJson(saved);
    jdbc.update("UPDATE auto_link_version SET body_json = ? WHERE version = ?", savedBody, version);
    return saved;
  }

  public Optional<AutoLinks> latestAutoLinks() {
    return jdbc.query("SELECT body_json FROM auto_link_version ORDER BY version DESC LIMIT 1",
        (rs, rowNum) -> read(rs.getString("body_json"), AutoLinks.class)).stream().findFirst();
  }

  public int currentDecisionVersion() {
    Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM manual_decision",
        Integer.class);
    return version == null ? 0 : version;
  }

  @Transactional
  public ManualDecisions.Decision appendDecision(ManualDecisions.Decision decision, String body) {
    jdbc.update("""
        INSERT INTO manual_decision(
          version, created_at, expected_version, action, target, device_id, offset_hz, note, evidence_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, decision.version(), decision.createdAt(), decision.version() - 1,
        decision.action().name(), decision.target(), decision.deviceId(), decision.offsetHz(),
        decision.note(), body);
    return decision;
  }

  public List<ManualDecisions.Decision> listDecisions() {
    return jdbc.query("SELECT evidence_json FROM manual_decision ORDER BY version",
        (rs, rowNum) -> read(rs.getString("evidence_json"), ManualDecisions.Decision.class));
  }

  public Optional<ManualDecisions.Decision> findDecision(int version) {
    return jdbc.query("SELECT evidence_json FROM manual_decision WHERE version = ?",
        (rs, rowNum) -> read(rs.getString("evidence_json"), ManualDecisions.Decision.class), version)
        .stream().findFirst();
  }

  @Transactional
  public void recordConflict(int expectedVersion, int actualVersion, String attempted, String existing) {
    jdbc.update("""
        INSERT INTO decision_conflict(created_at, expected_version, actual_version, attempted_json, existing_json)
        VALUES (?, ?, ?, ?, ?)
        """, System.currentTimeMillis(), expectedVersion, actualVersion, attempted, existing);
  }

  @Transactional
  public void savePublication(String publishId, int modelVersion, int autoVersion,
      int decisionVersion, long publishedAt, int eventCount, List<PublishedEventRow> rows) {
    jdbc.update("""
        INSERT INTO publish_batch(id, model_version, auto_version, decision_version, published_at, event_count)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO NOTHING
        """, publishId, modelVersion, autoVersion, decisionVersion, publishedAt, eventCount);
    for (PublishedEventRow row : rows) {
      jdbc.update("""
          INSERT INTO published_event(id, publish_id, alternate_id, selected, fingerprint, event_json)
          VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT(publish_id, alternate_id, id) DO NOTHING
          """, row.eventId(), publishId, row.alternateId(), row.selected() ? 1 : 0,
          row.fingerprint(), row.eventJson());
    }
  }

  public Optional<PublishBatchRecord> findPublication(String publishId) {
    return jdbc.query("SELECT * FROM publish_batch WHERE id = ?",
        (rs, rowNum) -> new PublishBatchRecord(rs.getString("id"), rs.getInt("model_version"),
            rs.getInt("auto_version"), rs.getInt("decision_version"),
            rs.getLong("published_at"), rs.getInt("event_count")), publishId).stream().findFirst();
  }

  public List<PublishedEventRow> listPublishedEvents(String publishId) {
    return jdbc.query("SELECT * FROM published_event WHERE publish_id = ? ORDER BY alternate_id, id",
        (rs, rowNum) -> new PublishedEventRow(rs.getString("id"), rs.getString("alternate_id"),
            rs.getInt("selected") == 1, rs.getString("fingerprint"), rs.getString("event_json")),
        publishId);
  }

  private RawSegment mapSegment(ResultSet rs, int rowNum) throws SQLException {
    return new RawSegment(
        rs.getString("id"),
        rs.getString("batch_id"),
        rs.getString("device_id"),
        rs.getLong("start_nanos"),
        rs.getLong("end_nanos"),
        rs.getLong("center_frequency_hz"),
        rs.getLong("resolution_bandwidth_hz"),
        rs.getInt("bucket_count"),
        rs.getBytes("power_buckets_gz"),
        rs.getDouble("device_temperature_c"),
        rs.getString("calibration_version"),
        rs.getString("content_hash"));
  }

  private <T> T read(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (Exception exception) {
      throw new IllegalStateException("Stored JSON is invalid", exception);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Cannot serialize value", exception);
    }
  }

  public record ImportBatchRecord(String id, String sourceHash, int segmentCount) {
  }

  public record PublishBatchRecord(
      String id, int modelVersion, int autoVersion, int decisionVersion,
      long publishedAt, int eventCount) {
  }

  public record PublishedEventRow(
      String eventId, String alternateId, boolean selected,
      String fingerprint, String eventJson) {
  }
}
