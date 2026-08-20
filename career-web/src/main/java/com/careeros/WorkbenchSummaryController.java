package com.careeros;

import com.careeros.application.workbench.WorkbenchSummary;
import com.careeros.application.workbench.WorkbenchSummaryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/workbench-summary")
final class WorkbenchSummaryController {
    private final WorkbenchSummaryService service;

    WorkbenchSummaryController(WorkbenchSummaryService service) {
        this.service = java.util.Objects.requireNonNull(service);
    }

    @GetMapping
    WorkbenchSummary summarize(@PathVariable("candidateId") UUID candidateId) {
        return service.summarize(candidateId);
    }
}
