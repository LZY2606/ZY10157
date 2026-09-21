package com.local.spectrum.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.spectrum.domain.AutoLinks;
import com.local.spectrum.domain.CorrectionModel;
import com.local.spectrum.domain.Edge;
import com.local.spectrum.domain.EventPlan;
import com.local.spectrum.domain.ManualDecisions;
import com.local.spectrum.domain.Observation;
import com.local.spectrum.dto.AssemblyView;
import com.local.spectrum.repository.SpectrumRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AssemblyService {
  private final SpectrumRepository repository;
  private final CorrectionService correctionService;
  private final ObservationService observationService;
  private final ObjectMapper objectMapper;

  public AssemblyService(SpectrumRepository repository, CorrectionService correctionService,
      ObservationService observationService, ObjectMapper objectMapper) {
    this.repository = repository;
    this.correctionService = correctionService;
    this.observationService = observationService;
    this.objectMapper = objectMapper;
  }

  public AssemblyView assemble() {
    CorrectionModel correction = correctionService.active();
    List<Observation> observations = observationService.extract(correction);
    List<Edge> rawEdges = StitchingEngine.edges(observations, objectMapper);
    AutoLinks autoLinks = repository.latestAutoLinks().orElse(null);
    ManualDecisions decisions = new ManualDecisions(repository.currentDecisionVersion(),
        repository.listDecisions());
    List<Edge> edges = applyDecisions(rawEdges, decisions.decisions());
    List<EventPlan> plans = buildPlans(observations, edges, decisions.decisions());
    return new AssemblyView(
        correction.version() == null ? 0 : correction.version(),
        autoLinks == null ? null : autoLinks.version(),
        decisions.version(),
        observations,
        edges,
        plans,
        correction,
        compatibleAuto(correction, autoLinks) ? autoLinks : null);
  }

  private List<Edge> applyDecisions(List<Edge> rawEdges, List<ManualDecisions.Decision> decisions) {
    AutoLinks autoLinks = repository.latestAutoLinks().orElse(null);
    CorrectionModel correction = correctionService.active();
    Set<String> autoStrong = new HashSet<>();
    boolean autoApplies = autoLinks != null && autoLinks.modelVersion() ==
        (correction.version() == null ? 0 : correction.version());
    if (autoApplies) {
      autoStrong.addAll(autoLinks.strongEdgeKeys());
    }
    Map<String, ManualDecisions.Action> latestAction = new LinkedHashMap<>();
    for (ManualDecisions.Decision decision : decisions) {
      if (decision.action() == ManualDecisions.Action.REJECT_EDGE
          || decision.action() == ManualDecisions.Action.FORCE_EDGE
          || decision.action() == ManualDecisions.Action.SPLIT_AT_EDGE) {
        latestAction.put(decision.target(), decision.action());
      }
    }
    return rawEdges.stream()
        .filter(edge -> latestAction.get(edge.key()) != ManualDecisions.Action.REJECT_EDGE)
        .filter(edge -> latestAction.get(edge.key()) != ManualDecisions.Action.SPLIT_AT_EDGE)
        .map(edge -> latestAction.get(edge.key()) == ManualDecisions.Action.FORCE_EDGE
            ? withStatus(edge, Edge.Status.FORCED.name())
            : autoApplies && Edge.Status.STRONG.name().equals(edge.status())
                ? withStatus(edge, autoStrong.contains(edge.key())
                    ? Edge.Status.STRONG.name() : Edge.Status.ALTERNATIVE.name())
                : edge)
        .sorted(Comparator.comparing(Edge::key))
        .toList();
  }

  private List<EventPlan> buildPlans(List<Observation> observations, List<Edge> edges,
      List<ManualDecisions.Decision> decisions) {
    Map<String, Observation> byId = new LinkedHashMap<>();
    observations.forEach(observation -> byId.put(observation.id(), observation));
    List<Edge> baseEdges = edges.stream()
        .filter(edge -> edge.status().equals(Edge.Status.STRONG.name())
            || edge.status().equals(Edge.Status.FORCED.name()))
        .toList();
    List<Edge> alternatives = edges.stream()
        .filter(edge -> edge.status().equals(Edge.Status.ALTERNATIVE.name()))
        .sorted(Comparator.comparing(Edge::key))
        .limit(4)
        .toList();
    String selectedPlan = decisions.stream()
        .filter(decision -> decision.action() == ManualDecisions.Action.SELECT_PLAN)
        .reduce((first, second) -> second)
        .map(ManualDecisions.Decision::target)
        .orElse(null);

    List<EventPlan> plans = new ArrayList<>();
    int combinationCount = 1 << alternatives.size();
    for (int mask = 0; mask < combinationCount; mask++) {
      List<Edge> planEdges = new ArrayList<>(baseEdges);
      List<String> edgeIds = new ArrayList<>();
      for (int index = 0; index < alternatives.size(); index++) {
        if ((mask & (1 << index)) != 0) {
          Edge edge = withStatus(alternatives.get(index), Edge.Status.FORCED.name());
          planEdges.add(edge);
          edgeIds.add(edge.key());
        }
      }
      String alternateId = Hashes.sha256("alt_", edgeIds, objectMapper);
      List<EventPlan.Event> events = components(byId, planEdges).stream()
          .map(component -> buildEvent(component.observations(), component.edges(), alternateId))
          .sorted(Comparator.comparing(EventPlan.Event::startNanos)
              .thenComparing(EventPlan.Event::frequencyLowHz)
              .thenComparing(EventPlan.Event::id))
          .toList();
      double planScore = events.stream().mapToDouble(EventPlan.Event::score).average().orElse(0.0d);
      Map<String, Double> contributions = new LinkedHashMap<>();
      contributions.put("averageEventScore", StitchingEngine.round(planScore));
      contributions.put("alternativeEdgesAccepted", (double) edgeIds.size());
      plans.add(new EventPlan(alternateId, alternateId.equals(selectedPlan), events,
          StitchingEngine.round(planScore), contributions));
    }
    plans.sort(Comparator.comparing(EventPlan::planScore).reversed()
        .thenComparing(EventPlan::alternateId));
    if (selectedPlan == null && !plans.isEmpty()) {
      EventPlan first = plans.get(0);
      plans.set(0, new EventPlan(first.alternateId(), true, first.events(), first.planScore(),
          first.scoreContributions()));
    }
    return plans;
  }

  private Edge withStatus(Edge edge, String status) {
    return new Edge(edge.key(), edge.observationIdA(), edge.observationIdB(),
        edge.deviceIdA(), edge.deviceIdB(), edge.crossDevice(), edge.frequencyOverlapHz(),
        edge.timeOverlapNanos(), edge.timeGapNanos(), edge.score(), edge.scoreContributions(),
        edge.uncertainty(), status, edge.reason());
  }

  private List<Component> components(Map<String, Observation> byId, List<Edge> edges) {
    java.util.Map<String, Set<String>> neighbors = new LinkedHashMap<>();
    byId.keySet().forEach(id -> neighbors.put(id, new LinkedHashSet<>()));
    for (Edge edge : edges) {
      neighbors.get(edge.observationIdA()).add(edge.observationIdB());
      neighbors.get(edge.observationIdB()).add(edge.observationIdA());
    }
    Set<String> visited = new HashSet<>();
    List<Component> components = new ArrayList<>();
    for (String root : byId.keySet()) {
      if (visited.contains(root)) continue;
      Set<String> ids = new LinkedHashSet<>();
      java.util.Deque<String> queue = new java.util.ArrayDeque<>(List.of(root));
      visited.add(root);
      while (!queue.isEmpty()) {
        String id = queue.removeFirst();
        ids.add(id);
        for (String neighbor : neighbors.get(id)) {
          if (visited.add(neighbor)) queue.add(neighbor);
        }
      }
      List<Observation> observations = ids.stream().map(byId::get)
          .sorted(Comparator.comparing(Observation::startNanos).thenComparing(Observation::id))
          .toList();
      Set<String> idSet = ids;
      List<Edge> componentEdges = edges.stream()
          .filter(edge -> idSet.contains(edge.observationIdA()))
          .sorted(Comparator.comparing(Edge::key))
          .toList();
      components.add(new Component(observations, componentEdges));
    }
    return components;
  }

  private EventPlan.Event buildEvent(List<Observation> observations, List<Edge> edges,
      String alternateId) {
    List<String> observationIds = observations.stream().map(Observation::id).toList();
    List<String> edgeKeys = edges.stream().map(Edge::key).toList();
    String fingerprint = Hashes.sha256("evt_", List.of(alternateId, observationIds, edgeKeys),
        objectMapper);
    long start = observations.stream().mapToLong(Observation::startNanos).min().orElseThrow();
    long end = observations.stream().mapToLong(Observation::endNanos).max().orElseThrow();
    double low = observations.stream().mapToDouble(Observation::frequencyLowHz).min().orElseThrow();
    double high = observations.stream().mapToDouble(Observation::frequencyHighHz).max().orElseThrow();
    double energy = unionEnergy(observations);
    double maxPower = observations.stream()
        .flatMap(observation -> observation.buckets().stream())
        .mapToDouble(Observation.BucketPower::dbm)
        .max().orElse(Double.NaN);
    double edgeAverage = edges.stream().mapToDouble(Edge::score).average().orElse(0.85d);
    Edge.Uncertainty uncertainty = aggregateUncertainty(observations, edges);
    double penalty = StitchingEngine.uncertaintyPenalty(uncertainty);
    double score = StitchingEngine.round(Math.max(0.0d, edgeAverage - penalty));
    Map<String, Double> contributions = new LinkedHashMap<>();
    contributions.put("edgeCoherence", StitchingEngine.round(edgeAverage));
    contributions.put("gapUncertainty", StitchingEngine.round(uncertainty.gapNanos()
        / (double) StitchingEngine.MAX_GAP_NANOS));
    contributions.put("saturation", uncertainty.saturatedBucketFraction());
    contributions.put("correctionExtrapolation", uncertainty.extrapolatedBucketFraction());
    contributions.put("clockUnidentifiable", uncertainty.clockUnidentifiableFraction());
    contributions.put("uncertaintyPenalty", penalty);
    return new EventPlan.Event(fingerprint, fingerprint, start, end, low, high,
        StitchingEngine.round(energy), StitchingEngine.round(maxPower), score, contributions,
        uncertainty, observationIds, edgeKeys, observations, edges);
  }

  private double unionEnergy(List<Observation> observations) {
    Set<Long> timeBounds = new LinkedHashSet<>();
    Set<Double> frequencyBounds = new LinkedHashSet<>();
    observations.forEach(observation -> {
      timeBounds.add(observation.startNanos());
      timeBounds.add(observation.endNanos());
      frequencyBounds.add(observation.frequencyLowHz());
      frequencyBounds.add(observation.frequencyHighHz());
    });
    List<Long> times = timeBounds.stream().sorted().toList();
    List<Double> frequencies = frequencyBounds.stream().sorted().toList();
    double energy = 0.0d;
    for (int timeIndex = 0; timeIndex < times.size() - 1; timeIndex++) {
      long t0 = times.get(timeIndex);
      long t1 = times.get(timeIndex + 1);
      for (int frequencyIndex = 0; frequencyIndex < frequencies.size() - 1; frequencyIndex++) {
        double f0 = frequencies.get(frequencyIndex);
        double f1 = frequencies.get(frequencyIndex + 1);
        double maxDbm = Double.NEGATIVE_INFINITY;
        for (Observation observation : observations) {
          if (t0 < observation.startNanos() || t1 > observation.endNanos()) continue;
          for (Observation.BucketPower bucket : observation.buckets()) {
            if (f0 + 1.0e-6d < bucket.lowHz() || f1 > bucket.highHz() + 1.0e-6d) continue;
            maxDbm = Math.max(maxDbm, bucket.dbm());
          }
        }
        if (maxDbm != Double.NEGATIVE_INFINITY) {
          energy += Math.pow(10.0d, maxDbm / 10.0d) * (f1 - f0) * (t1 - t0) / 1.0e9d;
        }
      }
    }
    return energy;
  }

  private Edge.Uncertainty aggregateUncertainty(List<Observation> observations, List<Edge> edges) {
    long maxGap = edges.stream().mapToLong(Edge::timeGapNanos).max().orElse(0L);
    double saturated = observations.stream()
        .flatMap(observation -> observation.buckets().stream())
        .filter(bucket -> bucket.dbm() >= ObservationService.SATURATED_DBM)
        .count() / (double) observations.stream().mapToInt(observation ->
            observation.buckets().size()).sum();
    double extrapolated = observations.stream().mapToDouble(observation ->
        observation.extrapolated() ? 1.0d : 0.0d).average().orElse(0.0d);
    double clock = observations.stream().filter(Observation::clockUnidentifiable).count()
        / (double) observations.size();
    return new Edge.Uncertainty(maxGap, StitchingEngine.round(saturated),
        StitchingEngine.round(extrapolated), StitchingEngine.round(clock));
  }

  private boolean compatibleAuto(CorrectionModel correction, AutoLinks autoLinks) {
    return autoLinks != null && autoLinks.modelVersion() ==
        (correction.version() == null ? 0 : correction.version());
  }

  private record Component(List<Observation> observations, List<Edge> edges) {
  }
}
