package com.local.spectrum.dto;

import com.local.spectrum.domain.EventPlan;
import com.local.spectrum.repository.SpectrumRepository;
import java.util.List;

public record PublicationView(
    String id,
    int modelVersion,
    int autoLinkVersion,
    int decisionVersion,
    long publishedAt,
    int eventCount,
    boolean idempotentReplay,
    List<EventPlan> plans) {

  public static PublicationView fromBatch(SpectrumRepository.PublishBatchRecord batch,
      List<EventPlan> plans, boolean replay) {
    return new PublicationView(batch.id(), batch.modelVersion(), batch.autoVersion(),
        batch.decisionVersion(), batch.publishedAt(), batch.eventCount(), replay, plans);
  }
}
