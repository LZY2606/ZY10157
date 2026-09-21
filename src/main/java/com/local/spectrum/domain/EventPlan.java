package com.local.spectrum.domain;

import java.util.List;
import java.util.Map;

public record EventPlan(
    String alternateId,
    boolean selected,
    List<Event> events,
    double planScore,
    Map<String, Double> scoreContributions) {

  public record Event(
      String id,
      String fingerprint,
      long startNanos,
      long endNanos,
      double frequencyLowHz,
      double frequencyHighHz,
      double energyMwHzSec,
      double maxPowerDbm,
      double score,
      Map<String, Double> scoreContributions,
      Edge.Uncertainty uncertainty,
      List<String> observationIds,
      List<String> edgeKeys,
      List<Observation> evidenceObservations,
      List<Edge> evidenceEdges) {
  }
}
