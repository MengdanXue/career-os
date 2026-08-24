package com.careeros;

import com.careeros.application.personal.CandidateDecisionDiffService;
import com.careeros.application.personal.DecisionChangeSummary;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/decision-change-summaries")
class DecisionChangeController {
    private final CandidateDecisionDiffService changes;

    DecisionChangeController(CandidateDecisionDiffService changes) {
        this.changes = changes;
    }

    @PostMapping("/{previousProfileVersion}")
    DecisionChangeSummary recompute(
        @PathVariable("candidateId") UUID candidateId,
        @PathVariable("previousProfileVersion") String previousProfileVersion,
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        return changes.recompute(candidateId, previousProfileVersion, asOf);
    }
}
