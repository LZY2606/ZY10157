package com.local.spectrum.web;

import com.local.spectrum.domain.AutoLinks;
import com.local.spectrum.domain.CorrectionModel;
import com.local.spectrum.domain.EventPlan;
import com.local.spectrum.domain.ManualDecisions;
import com.local.spectrum.dto.AssemblyView;
import com.local.spectrum.dto.CalibrationImpact;
import com.local.spectrum.dto.DecisionRequest;
import com.local.spectrum.dto.OffsetRequest;
import com.local.spectrum.dto.PublicationView;
import com.local.spectrum.dto.PublishRequest;
import com.local.spectrum.service.AssemblyService;
import com.local.spectrum.service.AutoLinkService;
import com.local.spectrum.service.CorrectionService;
import com.local.spectrum.service.DecisionService;
import com.local.spectrum.service.ImportService;
import com.local.spectrum.service.PublishService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SpectrumController {
  private final ImportService importService;
  private final AssemblyService assemblyService;
  private final CorrectionService correctionService;
  private final AutoLinkService autoLinkService;
  private final DecisionService decisionService;
  private final PublishService publishService;

  public SpectrumController(ImportService importService, AssemblyService assemblyService,
      CorrectionService correctionService, AutoLinkService autoLinkService,
      DecisionService decisionService, PublishService publishService) {
    this.importService = importService;
    this.assemblyService = assemblyService;
    this.correctionService = correctionService;
    this.autoLinkService = autoLinkService;
    this.decisionService = decisionService;
    this.publishService = publishService;
  }

  @PostMapping(path = "/imports", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/gzip"})
  public ImportService.ImportResult importBatch(@RequestBody byte[] body,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
    return importService.importPayload(body, "application/gzip".equalsIgnoreCase(contentType));
  }

  @PostMapping("/demo/fixture")
  public ImportService.ImportResult loadDemoFixture() throws IOException {
    byte[] body = new ClassPathResource("fixtures/demo-import.json")
        .getContentAsByteArray();
    return importService.importPayload(body, false);
  }

  @GetMapping("/assembly")
  public AssemblyView assembly() {
    return assemblyService.assemble();
  }

  @GetMapping("/corrections")
  public CorrectionModel correction() {
    return correctionService.active();
  }

  @PostMapping("/corrections")
  public CorrectionModel replaceCorrection(@RequestBody CorrectionModel model) {
    return correctionService.replaceModel(model);
  }

  @PostMapping("/corrections/device-offset")
  public CorrectionModel deviceOffset(@RequestBody OffsetRequest request) {
    if (request == null || request.deviceId() == null || request.deviceId().isBlank()) {
      throw new IllegalArgumentException("deviceId is required");
    }
    return correctionService.adjustDeviceOffset(
        request.deviceId(), request.calibrationVersion(), request.offsetHz(), request.note());
  }

  @GetMapping("/calibrations/{calibrationVersion}/affected")
  public CalibrationImpact affectedByCalibration(@PathVariable String calibrationVersion) {
    AssemblyView assembly = assemblyService.assemble();
    Set<String> affected = new LinkedHashSet<>();
    List<CalibrationImpact.EventSummary> summaries = new ArrayList<>();
    for (EventPlan plan : assembly.plans()) {
      for (EventPlan.Event event : plan.events()) {
        boolean usesCalibration = event.evidenceObservations().stream()
            .anyMatch(observation -> calibrationVersion.equals(observation.calibrationVersion()));
        if (usesCalibration && affected.add(event.fingerprint())) {
          summaries.add(new CalibrationImpact.EventSummary(
              event.id(), plan.alternateId(), event.startNanos(), event.endNanos(),
              event.frequencyLowHz(), event.frequencyHighHz(),
              "contains a segment with calibration " + calibrationVersion));
        }
      }
    }
    return new CalibrationImpact(calibrationVersion, List.copyOf(affected), List.of(),
        List.of(), summaries);
  }

  @PostMapping("/auto-links")
  public AutoLinks regenerateAutoLinks(@RequestBody(required = false) AutoLinks request) {
    return autoLinkService.regenerate(request == null ? null : request.note());
  }

  @GetMapping("/decisions")
  public ManualDecisions decisions() {
    return new ManualDecisions(
        decisionService.list().stream().mapToInt(ManualDecisions.Decision::version).max().orElse(0),
        decisionService.list());
  }

  @PostMapping("/decisions")
  public ManualDecisions.Decision decide(@RequestBody DecisionRequest request) {
    return decisionService.append(request);
  }

  @PostMapping("/publications")
  public PublicationView publish(@RequestBody(required = false) PublishRequest request) {
    return publishService.publish(request == null ? null : request.id());
  }

  @GetMapping("/publications/{id}")
  public PublicationView publication(@PathVariable String id) {
    return publishService.get(id);
  }
}
