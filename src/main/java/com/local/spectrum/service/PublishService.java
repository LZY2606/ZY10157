package com.local.spectrum.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.spectrum.domain.EventPlan;
import com.local.spectrum.dto.AssemblyView;
import com.local.spectrum.dto.PublicationView;
import com.local.spectrum.repository.SpectrumRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublishService {
  private final AssemblyService assemblyService;
  private final SpectrumRepository repository;
  private final ObjectMapper objectMapper;

  public PublishService(AssemblyService assemblyService, SpectrumRepository repository,
      ObjectMapper objectMapper) {
    this.assemblyService = assemblyService;
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public PublicationView publish(String requestedId) {
    AssemblyView assembly = assemblyService.assemble();
    String id = requestedId == null || requestedId.isBlank()
        ? Hashes.sha256("pub_", stableSource(assembly), objectMapper)
        : requestedId;
    var existing = repository.findPublication(id);
    if (existing.isPresent()) {
      return PublicationView.fromBatch(existing.get(), readPlans(id), true);
    }
    persist(id, assembly);
    return PublicationView.fromBatch(repository.findPublication(id).orElseThrow(), readPlans(id), false);
  }

  public PublicationView get(String id) {
    var batch = repository.findPublication(id)
        .orElseThrow(() -> new IllegalArgumentException("Publication not found: " + id));
    return PublicationView.fromBatch(batch, readPlans(id), true);
  }

  void persist(String id, AssemblyView assembly) {
    List<SpectrumRepository.PublishedEventRow> rows = new ArrayList<>();
    int eventCount = 0;
    for (EventPlan plan : assembly.plans()) {
      for (EventPlan.Event event : plan.events()) {
        rows.add(new SpectrumRepository.PublishedEventRow(
            event.id(),
            plan.alternateId(),
            plan.selected(),
            event.fingerprint(),
            write(event)));
        eventCount++;
      }
    }
    repository.savePublication(id, assembly.correctionVersion(),
        assembly.autoLinkVersion() == null ? 0 : assembly.autoLinkVersion(),
        assembly.decisionVersion(), System.currentTimeMillis(), eventCount, rows);
  }

  private List<EventPlan> readPlans(String publishId) {
    Map<String, List<EventPlan.Event>> byAlternate = new LinkedHashMap<>();
    Map<String, Boolean> selected = new LinkedHashMap<>();
    for (SpectrumRepository.PublishedEventRow row : repository.listPublishedEvents(publishId)) {
      EventPlan.Event event = read(row.eventJson(), EventPlan.Event.class);
      byAlternate.computeIfAbsent(row.alternateId(), ignored -> new ArrayList<>()).add(event);
      selected.put(row.alternateId(), row.selected());
    }
    List<EventPlan> plans = new ArrayList<>();
    for (Map.Entry<String, List<EventPlan.Event>> entry : byAlternate.entrySet()) {
      List<EventPlan.Event> events = entry.getValue().stream()
          .sorted(java.util.Comparator.comparing(EventPlan.Event::startNanos)
              .thenComparing(EventPlan.Event::frequencyLowHz)
              .thenComparing(EventPlan.Event::id))
          .toList();
      double score = events.stream().mapToDouble(EventPlan.Event::score).average().orElse(0.0d);
      plans.add(new EventPlan(entry.getKey(), selected.getOrDefault(entry.getKey(), false),
          events, StitchingEngine.round(score), Map.of("averageEventScore",
          StitchingEngine.round(score))));
    }
    plans.sort(java.util.Comparator.comparing(EventPlan::planScore).reversed()
        .thenComparing(EventPlan::alternateId));
    return plans;
  }

  private List<Object> stableSource(AssemblyView assembly) {
    return List.of(
        assembly.correctionVersion(),
        assembly.autoLinkVersion() == null ? 0 : assembly.autoLinkVersion(),
        assembly.decisionVersion(),
        assembly.observations().stream().map(observation -> List.of(
            observation.id(), observation.segmentId(), observation.startNanos(),
            observation.endNanos(), observation.frequencyLowHz(),
            observation.frequencyHighHz())).toList(),
        assembly.edges().stream().map(edge -> List.of(
            edge.key(), edge.status(), edge.score())).toList(),
        assembly.plans().stream().map(EventPlan::alternateId).toList());
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Cannot serialize published event", exception);
    }
  }

  private <T> T read(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (Exception exception) {
      throw new IllegalStateException("Stored publication JSON is invalid", exception);
    }
  }
}
