package com.careeros.application;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.AgentSession.SessionFilters;
import com.careeros.application.AgentSessionService.Reference.Outcome;
import com.careeros.application.DecisionPorts.DecisionBundle;
import com.careeros.application.DecisionPorts.JobContext;
import com.careeros.domain.*;
import com.careeros.domain.CandidateFacts.CandidateFactKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentSessionServiceTest {
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID CANDIDATE_ID = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T15:00:00Z"), ZoneOffset.UTC);
    private static final SessionFilters FILTERS =
        new SessionFilters(OpportunityTier.T1, "杭州", JobFamily.INFORMATION_SYSTEMS, 5);

    private InMemorySessions sessions;
    private InMemoryProfiles profiles;
    private AgentSessionService service;

    @BeforeEach void setUp() {
        sessions = new InMemorySessions();
        profiles = new InMemoryProfiles(candidate("profile-1"));
        service = new AgentSessionService(sessions, profiles, CLOCK);
    }

    // --- 序号只在记下来的顺序里解析 ---

    /** 用户说"第二个"，指的是他屏幕上那一份列表的第二个。 */
    @Test void anOrdinalResolvesAgainstTheListTheUserActuallySaw() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(first), bundle(second)));

        assertThat(service.resolveOrdinal(SESSION_ID, 2))
            .isEqualTo(new AgentSessionService.Reference(Outcome.RESOLVED, second));
        assertThat(service.resolveOrdinal(SESSION_ID, 1).jobPostingId()).isEqualTo(first);
    }

    /**
     * 资料改了，名次和结论都可能变。此时"第二个"不能重新排名求解——
     * 重新排出来的第二个可能是另一个岗位，而用户看不出系统换了个岗位在回答。
     */
    @Test void anOrdinalIsRefusedRatherThanReRankedAfterTheProfileChanges() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(first), bundle(second)));

        profiles.stored = candidate("profile-2");

        var reference = service.resolveOrdinal(SESSION_ID, 2);
        assertThat(reference.outcome()).isEqualTo(Outcome.STALE_LISTING);
        assertThat(reference.jobPostingId()).isNull();
    }

    @Test void anOrdinalOutsideTheListIsNotGuessed() {
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));

        assertThat(service.resolveOrdinal(SESSION_ID, 4).outcome()).isEqualTo(Outcome.OUT_OF_RANGE);
        assertThat(service.resolveOrdinal(SESSION_ID, 0).outcome()).isEqualTo(Outcome.OUT_OF_RANGE);
    }

    @Test void anUnknownSessionResolvesNothing() {
        assertThat(service.resolveOrdinal(UUID.randomUUID(), 1).outcome()).isEqualTo(Outcome.NO_SESSION);
    }

    /** 同一个岗位不能在一份列表里出现两次，否则序号不再唯一指向一个岗位。 */
    @Test void aRepeatedJobInOneListingIsRejected() {
        UUID job = UUID.randomUUID();
        assertThatThrownBy(() -> new AgentSession(SESSION_ID, CANDIDATE_ID, FILTERS,
            List.of(job, job), List.of(), "profile-1", Instant.now()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("repeat");
    }

    // --- 筛选条件与资料版本 ---

    @Test void theFiltersAndProfileVersionAreRemembered() {
        var session = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));

        assertThat(session.filters()).isEqualTo(FILTERS);
        assertThat(session.profileVersion()).isEqualTo("profile-1");
    }

    /**
     * 确认写入的乐观版本检查必须以"用户看到问题时的版本"为准。若拿此刻库里的版本去比，
     * 那个检查恒真，等于没检查。
     */
    @Test void theRememberedVersionIsTheOneTheUserSawNotTheCurrentOne() {
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));
        profiles.stored = candidate("profile-2");

        assertThat(service.profileVersionSeenBy(SESSION_ID)).contains("profile-1");
    }

    @Test void aSecondListingReplacesTheOrderButKeepsTheSession() {
        UUID first = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(first)));

        var narrowed = new SessionFilters(OpportunityTier.T2, "浙江", JobFamily.DATA, 3);
        var session = service.remember(SESSION_ID, CANDIDATE_ID, narrowed, List.of(bundle(later)));

        assertThat(session.sessionId()).isEqualTo(SESSION_ID);
        assertThat(session.filters()).isEqualTo(narrowed);
        assertThat(service.resolveOrdinal(SESSION_ID, 1).jobPostingId()).isEqualTo(later);
    }

    // --- 待确认事项 ---

    /** 待确认事项要记下是哪个字段、哪个岗位问的、原话是什么，用户回答时才对得上。 */
    @Test void pendingConfirmationsCarryTheFieldTheJobAndTheQuestion() {
        UUID job = UUID.randomUUID();
        var session = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(job,
            Map.of(RuleType.POLITICAL_AFFILIATION, new EligibilityAssessment.RuleResult(
                EligibilityStatus.NEEDS_CONFIRMATION, "候选人政治面貌尚未确认")))));

        assertThat(session.pendingConfirmations()).singleElement().satisfies(pending -> {
            assertThat(pending.factKey()).isEqualTo(CandidateFactKey.POLITICAL_AFFILIATION);
            assertThat(pending.jobPostingId()).isEqualTo(job);
            assertThat(pending.question()).contains("政治面貌");
        });
        assertThat(session.pendingFor(CandidateFactKey.POLITICAL_AFFILIATION)).isPresent();
    }

    /**
     * 要凭材料判断的条件不进"回一句就能解决"的清单。把学历列在那里，会让用户以为
     * 说一句"我是硕士"就算数。
     */
    @Test void aConditionThatNeedsDocumentsIsNotListedAsConversationallyAnswerable() {
        var session = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID(),
            Map.of(RuleType.EDUCATION, new EligibilityAssessment.RuleResult(
                       EligibilityStatus.NEEDS_CONFIRMATION, "候选人学历尚未确认"),
                   RuleType.EXPERIENCE, new EligibilityAssessment.RuleResult(
                       EligibilityStatus.NEEDS_CONFIRMATION, "候选人工作经历尚未确认")))));

        assertThat(session.pendingConfirmations()).isEmpty();
    }

    /** 已经满足的条件不该出现在待确认清单里。 */
    @Test void aSatisfiedConditionIsNotPending() {
        var session = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID(),
            Map.of(RuleType.POLITICAL_AFFILIATION, new EligibilityAssessment.RuleResult(
                EligibilityStatus.ELIGIBLE, "公告未限制政治面貌")))));

        assertThat(session.pendingConfirmations()).isEmpty();
    }

    /** 同一个字段被多个岗位问到，只列一次——用户回答一次就够了。 */
    @Test void oneFieldAskedByTwoJobsIsListedOnce() {
        var rule = Map.of(RuleType.POLITICAL_AFFILIATION, new EligibilityAssessment.RuleResult(
            EligibilityStatus.NEEDS_CONFIRMATION, "候选人政治面貌尚未确认"));
        var session = service.remember(SESSION_ID, CANDIDATE_ID, FILTERS,
            List.of(bundle(UUID.randomUUID(), rule), bundle(UUID.randomUUID(), rule)));

        assertThat(session.pendingConfirmations()).hasSize(1);
    }

    @Test void aSessionCannotBeReusedForAnotherCandidate() {
        service.remember(SESSION_ID, CANDIDATE_ID, FILTERS, List.of(bundle(UUID.randomUUID())));
        UUID other = UUID.randomUUID();
        profiles.others.put(other, candidate("profile-other", other));

        assertThatThrownBy(() -> service.remember(SESSION_ID, other, FILTERS, List.of(bundle(UUID.randomUUID()))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("another candidate");
    }

    // --- 固定装置 ---

    private static CandidateProfile candidate(String version) { return candidate(version, CANDIDATE_ID); }

    private static CandidateProfile candidate(String version, UUID id) {
        return new CandidateProfile(id, "候选人", PartialDate.month(1997, 4), EducationLevel.MASTER,
            Set.of("计算机科学"), 2027, 0, Set.of(), List.of("杭州"),
            Set.of(EmploymentType.ESTABLISHMENT), version);
    }

    private static DecisionBundle bundle(UUID jobId) { return bundle(jobId, Map.of()); }

    private static DecisionBundle bundle(UUID jobId, Map<RuleType, EligibilityAssessment.RuleResult> rules) {
        UUID organizationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant assessedAt = Instant.parse("2026-08-24T00:00:00Z");
        String fingerprint = "a".repeat(64);
        var evidence = List.of(UUID.randomUUID());
        var job = new JobPosting(jobId, eventId, organizationId, "J-1", "信息技术岗位",
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, "杭州", 1,
            EducationLevel.BACHELOR, Set.of(), Set.of(), null, null, null, Set.of(), "",
            "https://example.gov.cn/job", evidence);
        var organization = new Organization(organizationId, "杭州市信息中心", OrganizationType.PUBLIC_INSTITUTION,
            "市级", "浙江", "杭州", null, null, "https://example.gov.cn");
        var event = new RecruitmentEvent(eventId, "公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            LocalDate.of(2026, 8, 1), null, LocalDate.of(2026, 9, 1), job.sourceUrl(),
            EmploymentType.ESTABLISHMENT, evidence);
        var eligibility = new EligibilityAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId,
            EligibilityStatus.NEEDS_CONFIRMATION, rules, evidence, "test", assessedAt, "profile-1", fingerprint);
        var fit = new FitAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, List.of(), "test",
            "profile-1", fingerprint, assessedAt);
        var stability = new StabilityAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, List.of(), "test",
            "profile-1", fingerprint, assessedAt);
        var decision = new DecisionAssessment(UUID.randomUUID(), CANDIDATE_ID, jobId, eligibility.id(), fit.id(),
            stability.id(), EligibilityStatus.NEEDS_CONFIRMATION, OpportunityTier.T1, RecommendationStatus.REVIEW,
            fit.score(), stability.score(), 0, "test", "profile-1", fingerprint, assessedAt);
        return new DecisionBundle(eligibility, fit, stability, decision,
            new JobContext(job, organization, event, fingerprint, true));
    }

    private static final class InMemorySessions implements AgentSessionPorts.Sessions {
        private final Map<UUID, AgentSession> stored = new HashMap<>();
        public Optional<AgentSession> find(UUID sessionId) { return Optional.ofNullable(stored.get(sessionId)); }
        public AgentSession save(AgentSession session) { stored.put(session.sessionId(), session); return session; }
    }

    private static final class InMemoryProfiles implements RepositoryPorts.CandidateProfiles {
        private CandidateProfile stored;
        private final Map<UUID, CandidateProfile> others = new HashMap<>();
        InMemoryProfiles(CandidateProfile seed) { this.stored = seed; }
        public CandidateProfile save(CandidateProfile value) { stored = value; return value; }
        public Optional<CandidateProfile> findById(UUID id) {
            if (id.equals(CANDIDATE_ID)) return Optional.of(stored);
            return Optional.ofNullable(others.get(id));
        }
        public Optional<CandidateProfile> findByIdForUpdate(UUID id) { return findById(id); }
        public List<CandidateProfile> findAll() { return List.of(stored); }
        public void deleteById(UUID id) { }
    }
}
