package com.local.spectrum.dto;

import com.local.spectrum.domain.AutoLinks;
import com.local.spectrum.domain.CorrectionModel;
import com.local.spectrum.domain.Edge;
import com.local.spectrum.domain.EventPlan;
import com.local.spectrum.domain.Observation;
import java.util.List;

public record AssemblyView(
    int correctionVersion,
    Integer autoLinkVersion,
    int decisionVersion,
    List<Observation> observations,
    List<Edge> edges,
    List<EventPlan> plans,
    CorrectionModel correction,
    AutoLinks autoLinks) {
}
