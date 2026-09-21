package com.local.spectrum.service;

import com.local.spectrum.repository.SpectrumRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ConflictRecorder {
  private final SpectrumRepository repository;

  public ConflictRecorder(SpectrumRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(int expectedVersion, int actualVersion, String attempted, String existing) {
    repository.recordConflict(expectedVersion, actualVersion, attempted, existing);
  }
}
