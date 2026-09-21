package com.local.spectrum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpectrumApplication {
  public static void main(String[] args) throws IOException {
    String database = System.getenv().getOrDefault("SPECTRUM_DB", "./data/spectrum.db");
    Path parent = Path.of(database).toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    SpringApplication.run(SpectrumApplication.class, args);
  }
}
