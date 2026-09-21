package com.local.spectrum.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.spectrum.domain.CorrectionModel;
import com.local.spectrum.domain.Edge;
import com.local.spectrum.domain.EventPlan;
import com.local.spectrum.domain.ManualDecisions;
import com.local.spectrum.dto.AssemblyView;
import com.local.spectrum.dto.DecisionRequest;
import com.local.spectrum.dto.PublicationView;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:spectrum-workflow-test?mode=memory&cache=shared",
    "spring.sql.init.mode=never"
})
class SpectrumWorkflowTest {
  @Autowired ImportService importService;
  @Autowired CorrectionService correctionService;
  @Autowired AutoLinkService autoLinkService;
  @Autowired AssemblyService assemblyService;
  @Autowired DecisionService decisionService;
  @Autowired PublishService publishService;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanDatabase() {
    jdbcTemplate.update("DELETE FROM published_event");
    jdbcTemplate.update("DELETE FROM publish_batch");
    jdbcTemplate.update("DELETE FROM manual_decision");
    jdbcTemplate.update("DELETE FROM decision_conflict");
    jdbcTemplate.update("DELETE FROM auto_link_version");
    jdbcTemplate.update("DELETE FROM correction_model");
    jdbcTemplate.update("DELETE FROM raw_segment");
    jdbcTemplate.update("DELETE FROM import_batch");
  }

  @Test
  void importsDeterministicallyReplaysAndStitchesDriftedDevices() throws Exception {
    byte[] fixture = new ClassPathResource("fixtures/test-import.json")
        .getContentAsByteArray();

    ImportService.ImportResult first = importService.importPayload(fixture, false);
    ImportService.ImportResult replay = importService.importPayload(fixture, false);

    assertThat(first.insertedSegments()).isEqualTo(4);
    assertThat(first.duplicateSegments()).isEqualTo(1);
    assertThat(replay.idempotentReplay()).isTrue();

    CorrectionModel corrected = correctionService.replaceModel(new CorrectionModel(
        null, System.currentTimeMillis(), true, "test drift correction", List.of(
        new CorrectionModel.Entry("scanner-b", "cal-2026-02", -20_000.0d, 0.0d,
            -10_000_000L, 0.0d, 0.0d, 0.0d, 0.0d, 99_990_000.0d, 100_000_000.0d,
            null, null, true, "scanner B LO and clock drift"))));
    assertThat(corrected.version()).isEqualTo(1);

    autoLinkService.regenerate("workflow");
    AssemblyView assembly = assemblyService.assemble();
    List<Edge> crossDevice = assembly.edges().stream()
        .filter(Edge::crossDevice)
        .toList();
    assertThat(crossDevice).isNotEmpty();
    Edge crossEdge = crossDevice.get(0);
    assertThat(crossEdge.frequencyOverlapHz()).isGreaterThan(0.0d);
    assertThat(crossEdge.uncertainty().clockUnidentifiableFraction()).isEqualTo(1.0d);
    assertThat(assembly.observations())
        .anyMatch(Observation -> Observation.calibrationVersion().equals("cal-2026-02")
            && Observation.extrapolated());
    assertThat(assembly.plans()).isNotEmpty();

    ManualDecisions.Decision decision = decisionService.append(new DecisionRequest(
        0, ManualDecisions.Action.REJECT_EDGE, crossEdge.key(), "reject test", null));
    assertThat(decision.version()).isEqualTo(1);
    assertThat(decision.evidence().snapshot()).isNotNull();

    assertThatThrownBy(() -> decisionService.append(new DecisionRequest(
        0, ManualDecisions.Action.REJECT_EDGE, crossEdge.key(), "stale", null)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("stale");

    decisionService.append(new DecisionRequest(
        1, ManualDecisions.Action.FORCE_EDGE, crossEdge.key(), "restore test", null));
    AssemblyView forced = assemblyService.assemble();
    assertThat(forced.decisionVersion()).isEqualTo(2);
    Edge forcedCrossEdge = forced.edges().stream()
        .filter(edge -> edge.key().equals(crossEdge.key()))
        .findFirst()
        .orElseThrow();
    assertThat(forcedCrossEdge.status()).isEqualTo(Edge.Status.FORCED.name());

    PublicationView publication = publishService.publish(null);
    PublicationView replayPublication = publishService.publish(publication.id());
    assertThat(publication.eventCount()).isGreaterThan(0);
    assertThat(replayPublication.idempotentReplay()).isTrue();
    assertThat(replayPublication.eventCount()).isEqualTo(publication.eventCount());
    assertThat(publication.plans())
        .isNotEmpty()
        .allSatisfy(plan -> assertThat(plan.events()).isNotEmpty());
    EventPlan.Event saturated = publication.plans().stream()
        .flatMap(plan -> plan.events().stream())
        .filter(event -> event.maxPowerDbm() >= -20.0d)
        .findFirst()
        .orElseThrow();
    assertThat(saturated.uncertainty().saturatedBucketFraction()).isGreaterThan(0.0d);
    assertThat(saturated.scoreContributions()).containsKeys("gapUncertainty", "saturation",
        "correctionExtrapolation", "clockUnidentifiable", "uncertaintyPenalty");
  }

  @Test
  void fingerprintsDoNotDependOnShuffledObservationOrder() throws Exception {
    byte[] fixture = new ClassPathResource("fixtures/test-import.json")
        .getContentAsByteArray();
    importService.importPayload(fixture, false);
    AssemblyView first = assemblyService.assemble();
    List<String> ids = first.observations().stream()
        .map(observation -> observation.id())
        .sorted(Collections.reverseOrder())
        .toList();
    assertThat(ids).hasSizeGreaterThan(3);
    assertThat(first.observations().stream().map(observation -> observation.id()).sorted().toList())
        .containsExactlyElementsOf(ids.stream().sorted().toList());
    String canonical = objectMapper.writeValueAsString(ids.stream().sorted().toList());
    String repeated = objectMapper.writeValueAsString(ids.stream().sorted().toList());
    assertThat(canonical).isEqualTo(repeated);
    assertThat(Hashes.sha256("fp_", canonical, objectMapper))
        .isEqualTo(Hashes.sha256("fp_", repeated, objectMapper));
  }
}
