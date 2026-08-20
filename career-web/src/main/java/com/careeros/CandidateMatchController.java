package com.careeros;

import com.careeros.application.CandidateMatchService;
import com.careeros.application.CandidateMatchService.MatchPage;
import com.careeros.application.CandidateMatchService.MatchQuery;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/job-matches")
class CandidateMatchController {
    private final CandidateMatchService service;

    CandidateMatchController(CandidateMatchService service) {
        this.service = service;
    }

    @GetMapping
    MatchPage list(
        @PathVariable("candidateId") UUID candidateId,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        return service.list(candidateId, new MatchQuery(page, size), Instant.now());
    }
}
