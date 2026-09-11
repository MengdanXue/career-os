package com.careeros;

import com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength;
import com.careeros.application.personal.DecisionChangeSummary;
import com.careeros.application.personal.ProfileConfirmationService;
import com.careeros.application.personal.ProfileConfirmationService.ConfirmationRequest;
import com.careeros.application.personal.ProfileConfirmationService.DeclaredValue;
import com.careeros.application.personal.ProfileConfirmationService.Result;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.DomainEnums.ApplicationTimeStatus;
import com.careeros.domain.DomainEnums.Gender;
import com.careeros.domain.DomainEnums.PoliticalAffiliation;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 记录用户对"待确认"问题的回答。
 *
 * <p>整条链路是一个事务：资料写入与幂等台账必须一起提交。分开提交的话，两者之间崩溃会让
 * 重试误判成尚未写入，于是重复写一次、再推高一次资料版本。
 *
 * <p>这是写接口，与读接口分开：查询岗位、看待确认事项走各自的 GET，不经过这里。
 * 报名相关的动作不在这条链路上——系统不代替用户报名。
 */
@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/profile-confirmations")
class ProfileConfirmationController {
    private final ProfileConfirmationService confirmations;

    ProfileConfirmationController(ProfileConfirmationService confirmations) {
        this.confirmations = confirmations;
    }

    @PostMapping
    @Transactional
    ConfirmationResponse record(
        @PathVariable("candidateId") UUID candidateId,
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestBody ConfirmationBody body
    ) {
        var request = new ConfirmationRequest(candidateId, body.factKey(), declared(body),
            body.expectedProfileVersion(), body.idempotencyKey(), body.acknowledgedChange());
        var outcome = confirmations.record(request, asOf);
        return new ConfirmationResponse(outcome.result(), outcome.evidenceStrength(),
            outcome.profileVersionBefore(), outcome.profileVersionAfter(), outcome.message(),
            outcome.pendingChange() == null ? null : new PendingChange(
                outcome.pendingChange().factKey(), outcome.pendingChange().from(), outcome.pendingChange().to()),
            outcome.changes());
    }

    /**
     * 把回答解析成一个封闭类型的值。
     *
     * <p>解析失败就是 400，不猜测——把一句听不懂的回答勉强塞进某个字段，比直接说没听懂危险得多。
     */
    private static DeclaredValue declared(ConfirmationBody body) {
        if (body.value() == null || body.value().isBlank()) {
            throw new IllegalArgumentException("value is required");
        }
        String value = body.value().strip();
        return switch (body.factKey()) {
            case GENDER -> new DeclaredValue.OfGender(parse(Gender.class, value, "性别"));
            case POLITICAL_AFFILIATION -> new DeclaredValue.OfPoliticalAffiliation(
                parse(PoliticalAffiliation.class, value, "政治面貌"));
            case EMPLOYER_SETTLEMENT_AT_APPLICATION, SOCIAL_INSURANCE_AT_APPLICATION ->
                new DeclaredValue.OfApplicationTimeStatus(body.factKey(),
                    parse(ApplicationTimeStatus.class, value, "报名时状态"));
            default -> throw new IllegalArgumentException(
                "「" + body.factKey() + "」不能通过对话确认，需要补充材料");
        };
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + "的取值无法识别：" + value);
        }
    }

    /**
     * @param expectedProfileVersion 用户看到那个问题时的资料版本
     * @param idempotencyKey         同一次回答重试时带同一把钥匙
     * @param acknowledgedChange     是否已确认要覆盖一个不同的既有答案
     */
    record ConfirmationBody(
        CandidateFactKey factKey,
        String value,
        String expectedProfileVersion,
        String idempotencyKey,
        boolean acknowledgedChange
    ) {}

    record PendingChange(CandidateFactKey factKey, String from, String to) {}

    /**
     * @param evidenceStrength 恒为 SELF_REPORTED：对话里的确认是本人声明，不是官方核实。
     * @param changes          真实重算出来的变化；没有写入或重算未完成时为 null，
     *                         这里不会出现"确认后将解锁多少岗位"这类承诺。
     */
    record ConfirmationResponse(
        Result result,
        EvidenceStrength evidenceStrength,
        String profileVersionBefore,
        String profileVersionAfter,
        String message,
        PendingChange pendingChange,
        DecisionChangeSummary changes
    ) {}
}
