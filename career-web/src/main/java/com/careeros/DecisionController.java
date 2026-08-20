package com.careeros;

import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/job-decisions")
class DecisionController {
    private final DecisionIntelligenceService decisions;
    private final DecisionRankingService rankings;
    private final DecisionExplanationService explanations;

    DecisionController(DecisionIntelligenceService decisions,DecisionRankingService rankings,DecisionExplanationService explanations) {
        this.decisions=decisions; this.rankings=rankings; this.explanations=explanations;
    }

    @PostMapping("/{jobId}") DecisionApiModels.DecisionResponse assess(@PathVariable("candidateId") UUID candidateId,@PathVariable("jobId") UUID jobId) { return response(decisions.assess(candidateId,jobId,Instant.now())); }
    @GetMapping("/{jobId}") DecisionApiModels.DecisionResponse current(@PathVariable("candidateId") UUID candidateId,@PathVariable("jobId") UUID jobId) { return response(decisions.current(candidateId,jobId)); }

    @GetMapping DecisionApiModels.DecisionPage rank(
        @PathVariable("candidateId") UUID candidateId,
        @RequestParam(name="tier",required=false) OpportunityTier tier,
        @RequestParam(name="location",required=false) String location,
        @RequestParam(name="jobFamily",required=false) JobFamily jobFamily,
        @RequestParam(name="page",defaultValue="0") int page,
        @RequestParam(name="size",defaultValue="20") int size,
        @RequestParam(name="includeExcluded",defaultValue="false") boolean includeExcluded
    ) {
        var result=rankings.rank(candidateId,new DecisionRankingService.RankingQuery(tier,location,jobFamily,page,size,includeExcluded),Instant.now());
        return new DecisionApiModels.DecisionPage(result.items().stream().map(this::response).toList(),result.page(),result.size(),result.total(),DecisionApiModels.DISCLAIMER);
    }

    private DecisionApiModels.DecisionResponse response(com.careeros.application.DecisionPorts.DecisionBundle bundle) { return DecisionApiModels.DecisionResponse.from(bundle,explanations.explain(bundle)); }
}
