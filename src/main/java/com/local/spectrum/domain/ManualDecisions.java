package com.local.spectrum.domain;

import java.util.List;

public record ManualDecisions(int version, List<Decision> decisions) {

  public record Decision(
      int version,
      long createdAt,
      Action action,
      String target,
      String deviceId,
      Double offsetHz,
      String note,
      Evidence evidence) {
  }

  public enum Action {
    REJECT_EDGE,
    FORCE_EDGE,
    SPLIT_AT_EDGE,
    SELECT_PLAN
  }

  public record Evidence(String edgeKey, String proposedBy, Object snapshot) {
  }
}
