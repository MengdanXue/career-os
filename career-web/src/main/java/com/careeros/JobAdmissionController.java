package com.careeros;

import com.careeros.application.AdmissionScopeReviewService;
import com.careeros.application.AdmissionScopeReviewService.Command;
import com.careeros.application.AdmissionScopeReviewService.Decision;
import com.careeros.application.AdmissionScopeReviewService.RecordedDecision;
import com.careeros.domain.DomainEnums.DataQualityStatus;
import com.careeros.domain.DomainEnums.JobAdmissionReason;
import com.careeros.domain.DomainEnums.TargetScopeStatus;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 可信机会池的入口：复核人确认一个岗位在不在目标范围内。
 *
 * <p>只有 REVIEWER／ADMIN 能写（见 {@link SecurityConfiguration}）。复核人是谁取自认证身份，
 * 不取自请求体——否则任何人都能以别人的名义把岗位放进池子。
 */
@RestController
@RequestMapping("/api/v1/job-admissions")
class JobAdmissionController {
    private final AdmissionScopeReviewService service;
    private final com.careeros.application.OfficialJobAdmissionService admissions;

    JobAdmissionController(AdmissionScopeReviewService service,
                           com.careeros.application.OfficialJobAdmissionService admissions) {
        this.service = service;
        this.admissions = admissions;
    }

    /**
     * 按当前规则重新评估全部在招岗位的自动准入，人工决定保留。
     *
     * <p>自动准入只在导入时运行；规则或证据读取更新后，旧岗位停在旧结论上，初筛队列不完整。
     * 分批提交，不开一个覆盖全库的大事务。
     */
    @PostMapping("/reclassifications")
    com.careeros.application.OfficialJobAdmissionService.Reclassification reclassify(
        @RequestParam(name = "batchSize", defaultValue = "500") int batchSize
    ) {
        return admissions.reclassifyActive(Instant.now(), batchSize);
    }

    @PostMapping("/{jobId}/scope-decisions")
    @Transactional
    ScopeDecisionResponse decide(@PathVariable("jobId") UUID jobId, @RequestBody ScopeDecisionRequest request,
                                 Principal principal) {
        if (principal == null) throw new IllegalArgumentException("需要认证身份才能记录复核人");
        var outcome = service.decide(new Command(jobId, request.decision(), request.contentFingerprint(),
            principal.getName(), request.note()));
        var admission = outcome.admission();
        return new ScopeDecisionResponse(jobId, outcome.decision().decisionId(), request.decision(),
            outcome.admitted(), admission.dataQualityStatus(), admission.targetScopeStatus(),
            admission.reasonCodes(), outcome.decision().reviewer(), outcome.decision().decidedAt());
    }

    @GetMapping("/{jobId}/scope-decisions")
    List<RecordedDecision> history(@PathVariable("jobId") UUID jobId) {
        return service.history(jobId);
    }

    record ScopeDecisionRequest(Decision decision, String contentFingerprint, String note) {}

    /**
     * @param admitted 这次决定之后它是否真的在可信池里（VERIFIED + INCLUDED 且用工身份已知）
     */
    record ScopeDecisionResponse(UUID jobId, UUID decisionId, Decision decision, boolean admitted,
                                 DataQualityStatus dataQualityStatus, TargetScopeStatus targetScopeStatus,
                                 Set<JobAdmissionReason> reasonCodes, String reviewer, Instant decidedAt) {}
}
