package com.local.spectrum.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.spectrum.domain.ManualDecisions;
import com.local.spectrum.dto.DecisionRequest;
import com.local.spectrum.repository.SpectrumRepository;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DecisionService {
  private final SpectrumRepository repository;
  private final AssemblyService assemblyService;
  private final ConflictRecorder conflictRecorder;
  private final ObjectMapper objectMapper;

  public DecisionService(SpectrumRepository repository, AssemblyService assemblyService,
      ConflictRecorder conflictRecorder, ObjectMapper objectMapper) {
    this.repository = repository;
    this.assemblyService = assemblyService;
    this.conflictRecorder = conflictRecorder;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public ManualDecisions.Decision append(DecisionRequest request) {
    validate(request);
    int current = repository.currentDecisionVersion();
    if (request.expectedVersion() != current) {
      ManualDecisions.Decision existing = repository.findDecision(current).orElse(null);
      conflictRecorder.record(request.expectedVersion(), current, write(request), write(existing));
      throw new ConflictException("Decision version " + request.expectedVersion()
          + " is stale; current version is " + current);
    }
    try {
      Object edgeEvidence = findEdgeEvidence(request.target());
      ManualDecisions.Evidence evidence = new ManualDecisions.Evidence(
          request.target(),
          request.note() == null ? "manual" : request.note(),
          request.evidence() == null ? edgeEvidence : request.evidence());
      ManualDecisions.Decision decision = new ManualDecisions.Decision(
          current + 1,
          System.currentTimeMillis(),
          request.action(),
          request.target(),
          null,
          null,
          request.note() == null || request.note().isBlank() ? "manual decision" : request.note(),
          evidence);
      return repository.appendDecision(decision, write(decision));
    } catch (DuplicateKeyException exception) {
      ManualDecisions.Decision existing = repository.findDecision(current + 1).orElse(null);
      conflictRecorder.record(request.expectedVersion(), current + 1, write(request), write(existing));
      throw new ConflictException("Decision version " + request.expectedVersion()
          + " lost a concurrent race; current version is " + (current + 1));
    }
  }

  public List<ManualDecisions.Decision> list() {
    return repository.listDecisions();
  }

  private Object findEdgeEvidence(String target) {
    return assemblyService.assemble().edges().stream()
        .filter(edge -> edge.key().equals(target))
        .findFirst()
        .map(edge -> (Object) edge)
        .orElse(null);
  }

  private void validate(DecisionRequest request) {
    if (request == null || request.action() == null || request.target() == null
        || request.target().isBlank()) {
      throw new IllegalArgumentException("action and target are required");
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Cannot serialize decision", exception);
    }
  }
}
