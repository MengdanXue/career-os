package com.careeros;

import com.careeros.application.AgentQueryService;
import com.careeros.application.DecisionExplanationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/agent-queries")
class DecisionAgentController {
    private final AgentQueryService service;
    private final DecisionExplanationService explanations;
    DecisionAgentController(AgentQueryService service,DecisionExplanationService explanations) { this.service=service; this.explanations=explanations; }

    @PostMapping AgentResponse query(@PathVariable("candidateId") UUID candidateId,@RequestBody AgentRequest request) {
        int limit=request.limit()==null?5:request.limit();
        var result=service.query(candidateId,request.question(),limit,Instant.now());
        var decisions=result.decisions().stream().map(value->DecisionApiModels.DecisionResponse.from(value,explanations.explain(value))).toList();
        return new AgentResponse(result.question(),result.answer(),decisions,result.modelPhrased(),result.fallbackUsed(),result.disclaimer());
    }

    record AgentRequest(String question,Integer limit) {}
    record AgentResponse(String question,String answer,List<DecisionApiModels.DecisionResponse> decisions,boolean modelPhrased,boolean fallbackUsed,String disclaimer) { AgentResponse { decisions=List.copyOf(decisions); } }
}
