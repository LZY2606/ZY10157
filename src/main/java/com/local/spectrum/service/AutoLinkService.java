package com.local.spectrum.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.spectrum.domain.AutoLinks;
import com.local.spectrum.domain.CorrectionModel;
import com.local.spectrum.domain.Edge;
import com.local.spectrum.domain.Observation;
import com.local.spectrum.repository.SpectrumRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutoLinkService {
  private final SpectrumRepository repository;
  private final CorrectionService correctionService;
  private final ObservationService observationService;
  private final ObjectMapper objectMapper;

  public AutoLinkService(SpectrumRepository repository, CorrectionService correctionService,
      ObservationService observationService, ObjectMapper objectMapper) {
    this.repository = repository;
    this.correctionService = correctionService;
    this.observationService = observationService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public AutoLinks regenerate(String note) {
    CorrectionModel model = correctionService.active();
    List<Observation> observations = observationService.extract(model);
    List<String> strongEdges = StitchingEngine.edges(observations, objectMapper).stream()
        .filter(edge -> Edge.Status.STRONG.name().equals(edge.status()))
        .map(Edge::key)
        .toList();
    AutoLinks autoLinks = new AutoLinks(null, System.currentTimeMillis(),
        model.version() == null ? 0 : model.version(),
        note == null || note.isBlank() ? "deterministic automatic links" : note,
        strongEdges);
    String body;
    try {
      body = objectMapper.writeValueAsString(autoLinks);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Cannot serialize auto links", exception);
    }
    return repository.saveAutoLinks(body, autoLinks.modelVersion(), autoLinks.note());
  }
}
