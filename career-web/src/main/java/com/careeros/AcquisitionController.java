package com.careeros;

import static com.careeros.AcquisitionApiModels.*;

import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.application.AcquisitionPorts.RunQuery;
import com.careeros.application.AcquisitionService;
import com.careeros.domain.acquisition.AcquisitionChange.ChangeType;
import com.careeros.domain.acquisition.SourceCrawlRun.RunStatus;
import com.careeros.domain.acquisition.SourceCrawlRun.RunTrigger;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/acquisition")
final class AcquisitionController {
    private final AcquisitionStore store;
    private final AcquisitionService service;
    private final com.careeros.application.SourceCompletionAuditService auditService;

    AcquisitionController(AcquisitionStore store, AcquisitionService service) {
        this(store, service, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    AcquisitionController(AcquisitionStore store, AcquisitionService service,
        com.careeros.application.SourceCompletionAuditService auditService) {
        this.store=java.util.Objects.requireNonNull(store);
        this.service=java.util.Objects.requireNonNull(service);
        this.auditService=auditService;
    }

    @PostMapping(value="/audit-snapshots", consumes=MediaType.APPLICATION_JSON_VALUE,
        produces=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> createAuditSnapshot(@RequestBody(required=false) AuditSnapshotRequest request) {
        if (auditService == null) throw new IllegalStateException("audit service is not configured");
        var value = request == null ? new AuditSnapshotRequest(null, null, null, null, null) : request;
        var created = auditService.create(value.fromYear(), value.toYear(), value.coverageThrough(), value.selectedCodes(), value.runIds());
        return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON)
            .header("Location", "/api/acquisition/audit-snapshots/" + created.id()).body(created.payload());
    }

    @GetMapping(value="/audit-snapshots/{snapshotId}", produces=MediaType.APPLICATION_JSON_VALUE)
    String auditSnapshot(@PathVariable("snapshotId") UUID snapshotId) { return auditService.get(snapshotId); }

    @GetMapping(value="/audit-snapshots/latest", produces=MediaType.APPLICATION_JSON_VALUE)
    String latestAuditSnapshot(@RequestParam("fromYear") int fromYear, @RequestParam("toYear") int toYear) {
        return auditService.latest(fromYear, toYear);
    }

    record AuditSnapshotRequest(Integer fromYear, Integer toYear, java.time.LocalDate coverageThrough,
        List<String> selectedCodes, List<UUID> runIds) {}

    @GetMapping("/sources")
    List<SourceResponse> sources() {
        var actual = store.findSources().stream().collect(java.util.stream.Collectors.toMap(
            com.careeros.domain.acquisition.RecruitmentSource::code, value -> value));
        var targets = store.findTargetSources();
        if (targets.isEmpty()) {
            return actual.values().stream().sorted(java.util.Comparator.comparing(
                    com.careeros.domain.acquisition.RecruitmentSource::code))
                .map(source -> SourceResponse.from(source, new com.careeros.application.AcquisitionPorts.TargetSourceRegistration(
                    source.code(), source.name(), source.region(), source.baseUri().toString(),
                    store.findTargetSourceStatus(source.code()), source.id(), source.enabled(),
                    "CITY", "HANGZHOU", "P0", "PRIMARY"), store)).toList();
        }
        return targets.stream().map(target -> SourceResponse.from(actual.get(target.code()), target, store)).toList();
    }

    @PostMapping("/sources/{sourceId}/runs")
    ResponseEntity<RunResponse> trigger(@PathVariable("sourceId") UUID sourceId) {
        RunResponse response = RunResponse.from(service.run(sourceId, RunTrigger.MANUAL));
        return ResponseEntity.accepted().location(URI.create("/api/acquisition/runs/" + response.id())).body(response);
    }

    @PostMapping("/sources/{sourceId}/historical-runs")
    ResponseEntity<HistoricalRunResponse> triggerHistorical(
        @PathVariable("sourceId") UUID sourceId,
        @RequestParam(name="fromYear") int fromYear,
        @RequestParam(name="toYear") int toYear
    ) {
        if (fromYear < 2000 || toYear > 2100 || fromYear > toYear) {
            throw new IllegalArgumentException("historical year range must be between 2000 and 2100");
        }
        Set<Integer> years = java.util.stream.IntStream.rangeClosed(fromYear, toYear)
            .boxed().collect(java.util.stream.Collectors.toUnmodifiableSet());
        RunResponse run = RunResponse.from(service.backfill(sourceId, years));
        List<CoverageResponse> coverage = store.findSourceYearCoverage(sourceId, null).stream()
            .filter(value -> years.contains(value.recruitmentYear()))
            .sorted(java.util.Comparator.comparingInt(com.careeros.domain.acquisition.SourceYearCoverage::recruitmentYear))
            .map(CoverageResponse::from).toList();
        return ResponseEntity.accepted().location(URI.create("/api/acquisition/runs/" + run.id()))
            .body(new HistoricalRunResponse(run, coverage));
    }

    @GetMapping("/runs/{runId}")
    RunResponse findRun(@PathVariable("runId") UUID runId) { return RunResponse.from(store.findRun(runId)); }

    @GetMapping("/runs")
    RunPageResponse runs(
        @RequestParam(name="sourceId", required=false) UUID sourceId,
        @RequestParam(name="status", required=false) RunStatus status,
        @RequestParam(name="from", required=false) Instant from,
        @RequestParam(name="to", required=false) Instant to,
        @RequestParam(name="page", defaultValue="0") int page,
        @RequestParam(name="size", defaultValue="50") int size
    ) {
        if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Invalid run page");
        return RunPageResponse.from(store.findRuns(new RunQuery(sourceId,status,from,to),page,size));
    }

    @GetMapping("/changes")
    ChangePageResponse changes(
        @RequestParam(name="cursor", required=false) String cursor,
        @RequestParam(name="sourceId", required=false) UUID sourceId,
        @RequestParam(name="types", required=false) Set<ChangeType> types,
        @RequestParam(name="size", defaultValue="50") int size
    ) {
        if (size < 1 || size > 200) throw new IllegalArgumentException("size must be between 1 and 200");
        return ChangePageResponse.from(store.findChanges(CursorCodec.decode(cursor), sourceId,
            types == null ? Set.of() : Set.copyOf(types), size));
    }

    @GetMapping("/coverage")
    List<CoverageResponse> coverage(
        @RequestParam(name="sourceId", required=false) UUID sourceId,
        @RequestParam(name="year", required=false) Integer year
    ) {
        if (year != null && (year < 2000 || year > 2100)) {
            throw new IllegalArgumentException("year must be between 2000 and 2100");
        }
        return store.findSourceYearCoverage(sourceId, year).stream().map(CoverageResponse::from).toList();
    }

    @GetMapping("/sources/{sourceId}/failures")
    List<FailureResponse> failures(
        @PathVariable("sourceId") UUID sourceId,
        @RequestParam(name="size", defaultValue="50") int size
    ) {
        if (size < 1 || size > 200) throw new IllegalArgumentException("size must be between 1 and 200");
        return store.findImportFailures(sourceId, null).stream().limit(size).map(FailureResponse::from).toList();
    }
}
