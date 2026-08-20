package com.careeros;

import com.careeros.application.JobLibrarySummaryService;
import com.careeros.application.JobLibrarySummaryService.JobLibrarySummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/job-library")
class JobLibrarySummaryController {
    private final JobLibrarySummaryService service;

    JobLibrarySummaryController(JobLibrarySummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    JobLibrarySummary summary() {
        return service.load();
    }
}
