package com.local.spectrum.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:sqlite:file:spectrum-api-test?mode=memory&cache=shared",
    "spring.sql.init.mode=never"
})
class SpectrumApiTest {
  @LocalServerPort int port;
  @Autowired TestRestTemplate restTemplate;
  @Autowired JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanDatabase() {
    jdbcTemplate.execute("DELETE FROM published_event");
    jdbcTemplate.execute("DELETE FROM publish_batch");
    jdbcTemplate.execute("DELETE FROM manual_decision");
    jdbcTemplate.execute("DELETE FROM decision_conflict");
    jdbcTemplate.execute("DELETE FROM auto_link_version");
    jdbcTemplate.execute("DELETE FROM correction_model");
    jdbcTemplate.execute("DELETE FROM raw_segment");
    jdbcTemplate.execute("DELETE FROM import_batch");
  }

  @Test
  void servesUiAndCompletesIdempotentWorkflowOverHttp() throws Exception {
    String base = "http://127.0.0.1:" + port;
    ResponseEntity<String> page = restTemplate.getForEntity(base + "/", String.class);
    assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(page.getBody()).contains("Pair-wise GSB");

    byte[] fixture = new ClassPathResource("fixtures/test-import.json").getContentAsByteArray();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> imported = restTemplate.postForEntity(base + "/api/imports",
        new HttpEntity<>(fixture, headers), String.class);
    assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(imported.getBody()).contains("\"insertedSegments\":4");
    ResponseEntity<String> replay = restTemplate.postForEntity(base + "/api/imports",
        new HttpEntity<>(fixture, headers), String.class);
    assertThat(replay.getBody()).contains("\"idempotentReplay\":true");

    ResponseEntity<String> assembly = restTemplate.getForEntity(base + "/api/assembly", String.class);
    assertThat(assembly.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(assembly.getBody()).contains("frequencyOverlap");

    String badDecision = """
        {"expectedVersion":99,"action":"REJECT_EDGE","target":"edge_does_not_exist"}
        """;
    ResponseEntity<String> conflict = restTemplate.postForEntity(base + "/api/decisions",
        new HttpEntity<>(badDecision, headers), String.class);
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody()).contains("version_conflict");
    Integer conflicts = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM decision_conflict", Integer.class);
    assertThat(conflicts).isGreaterThan(0);

    ResponseEntity<String> published = restTemplate.postForEntity(base + "/api/publications",
        new HttpEntity<>("{}", headers), String.class);
    assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(published.getBody()).contains("\"eventCount\"");
  }
}
