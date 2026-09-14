package com.careeros;

import com.careeros.application.personal.CandidateEvidenceTask.EvidenceStrength;
import com.careeros.application.personal.DecisionChangeSummary;
import com.careeros.application.personal.ProfileConfirmationService;
import com.careeros.application.personal.ProfileConfirmationService.ConfirmationRequest;
import com.careeros.application.personal.ProfileConfirmationService.DeclaredValue;
import com.careeros.application.personal.ProfileConfirmationService.Result;
import com.careeros.application.AgentSessionService;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import com.careeros.domain.DomainEnums.ApplicationTimeStatus;
import com.careeros.domain.DomainEnums.Gender;
import com.careeros.domain.DomainEnums.PoliticalAffiliation;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final AgentSessionService sessions;
    private final TransactionTemplate transactions;

    ProfileConfirmationController(ProfileConfirmationService confirmations, AgentSessionService sessions,
                                  TransactionTemplate transactions) {
        this.confirmations = confirmations;
        this.sessions = sessions;
        this.transactions = transactions;
    }

    /**
     * 两段式，不能合成一个事务。
     *
     * <p>第一段在事务里写资料和台账并提交。第二段在事务之外重算。
     *
     * <p>合成一个事务会毁掉失败恢复，而且不是理论问题：重算内部的评估失败会把整个事务标成
     * rollback-only，服务层 catch 住异常、返回"已记录但未重算"之后，提交阶段仍然整体回滚，
     * 接口抛 {@code UnexpectedRollbackException} 返 500，用户的回答连同台账一起消失。
     * 真机注入一次评估失败即可复现——单元测试里的假评估器只是抛异常，没有事务，照不出这一条。
     */
    @PostMapping
    ConfirmationResponse record(
        @PathVariable("candidateId") UUID candidateId,
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestBody ConfirmationBody body
    ) {
        var request = new ConfirmationRequest(candidateId, body.factKey(), declared(body),
            expectedProfileVersion(candidateId, body), body.idempotencyKey(), body.acknowledgedChange());
        var recorded = transactions.execute(status -> confirmations.recordAnswer(request));
        // 写入已提交。到这里重算再失败，也只是"结论还没刷新"，回答不会丢。
        var outcome = confirmations.completeRecompute(recorded, asOf);
        // 用户自己的这次写入把资料版本推高了。会话里记的还是查询时那一版，不推进的话
        // 下一个待确认问题必定撞上版本检查——连续确认走不完第二步。
        // 只从这次写入的 before 推进到 after：期间若有别处改动，会话版本已不是 before，
        // 这里什么都不做，版本检查照样会拦。
        if (body.sessionId() != null) {
            sessions.advanceProfileVersion(candidateId, body.sessionId(),
                outcome.profileVersionBefore(), outcome.profileVersionAfter());
        }
        return new ConfirmationResponse(outcome.result(), outcome.evidenceStrength(),
            outcome.profileVersionBefore(), outcome.profileVersionAfter(), outcome.message(),
            outcome.pendingChange() == null ? null : new PendingChange(
                outcome.pendingChange().factKey(), outcome.pendingChange().from(), outcome.pendingChange().to()),
            outcome.changes());
    }

    /**
     * 用户是在哪一版资料下看到那个问题的。
     *
     * <p>带了会话就以会话里记着的版本为准，客户端传来的版本一概不用——那个检查的意义正是
     * "用户看到的和现在的不一样"，若允许客户端自报，它只要报上当前版本就永远通过。
     *
     * <p>没有会话的直连调用（资料页自己的表单）才用请求里的版本：那里没有会话可依，
     * 版本是页面渲染时拿到的，仍然是"用户看到的那一版"。
     */
    private String expectedProfileVersion(UUID candidateId, ConfirmationBody body) {
        if (body.sessionId() == null) return body.expectedProfileVersion();
        // 按候选人取，不只按会话 ID：会话 ID 可猜，不核对归属就等于允许拿别人的版本来过自己的检查。
        return sessions.profileVersionSeenBy(candidateId, body.sessionId()).orElseThrow(() ->
            new IllegalArgumentException("会话不存在或已过期，请重新查询后再确认"));
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
     * @param sessionId              对话里回答时带上；带了它就以会话记着的资料版本为准
     * @param expectedProfileVersion 没有会话时（资料页直连）用户看到那个问题时的资料版本
     * @param idempotencyKey         同一次回答重试时带同一把钥匙
     * @param acknowledgedChange     是否已确认要覆盖一个不同的既有答案
     */
    record ConfirmationBody(
        CandidateFactKey factKey,
        String value,
        UUID sessionId,
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
