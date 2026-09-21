package com.local.spectrum.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaInitializer {
  private final JdbcTemplate jdbcTemplate;

  public SchemaInitializer(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @PostConstruct
  public void initialize() throws IOException {
    String schema = new ClassPathResource("schema.sql")
        .getContentAsString(StandardCharsets.UTF_8);
    for (String statement : schema.split(";\\s*")) {
      if (!statement.isBlank()) {
        jdbcTemplate.execute(statement);
      }
    }
  }
}
