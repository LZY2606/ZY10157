package com.local.spectrum.dto;

import com.local.spectrum.domain.ManualDecisions;

public record DecisionRequest(
    int expectedVersion,
    ManualDecisions.Action action,
    String target,
    String note,
    Object evidence) {
}
