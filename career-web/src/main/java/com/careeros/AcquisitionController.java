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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/acquisition")
final class AcquisitionController {
    private final AcquisitionStore store;
    private final AcquisitionService service;

    AcquisitionController(AcquisitionStore store, AcquisitionService service) {
        this.store=java.util.Objects.requireNonNull(store);
        this.service=java.util.Objects.requireNonNull(service);
    }

    @GetMapping("/sources")
    List<SourceResponse> sources() {
        return store.findSources().stream().map(SourceResponse::from).toList();
    }

    @PostMapping("/sources/{sourceId}/runs")
    ResponseEntity<RunResponse> trigger(@PathVariable("sourceId") UUID sourceId) {
        RunResponse response = RunResponse.from(service.run(sourceId, RunTrigger.MANUAL));
        return ResponseEntity.accepted().location(URI.create("/api/acquisition/runs/" + response.id())).body(response);
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
}
