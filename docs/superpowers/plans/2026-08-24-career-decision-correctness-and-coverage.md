# Career Decision Correctness and Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct 2027 overseas fresh-graduate decisions, expose complete recruitment/exam evidence, distinguish configured coverage from target-market coverage, connect the first 20 official source targets, and make the user's in-app plan actionable.

**Architecture:** Add structured graduate and process facts to the existing domain and recruitment-event model, project historical relative cohort rules into the target year without changing historical truth, and return explicit coverage/ranking states from the planning read model. Keep deterministic qualification logic in Java, use the existing PostgreSQL/Flyway evidence pipeline, and evolve the React pages through backward-compatible API contracts.

**Tech Stack:** Java 21, Spring Boot 3, PostgreSQL 16, Flyway, Jsoup, JUnit 5, AssertJ, Testcontainers, React 19, TypeScript 7, TanStack Query, Vitest, Testing Library, Playwright browser acceptance.

**Spec:** `docs/superpowers/specs/2026-08-24-career-decision-correctness-and-coverage-design.md`

## Global Constraints

- Historical facts and target-year analog projections must remain separate.
- Fresh-graduate status and overseas degree verification are orthogonal dimensions.
- Official notices, attachments, official registration systems, and official organization sites are decision evidence; third-party pages are discovery-only.
- `UNKNOWN`, `NOT_PUBLISHED`, `NOT_COLLECTED`, `PARSE_FAILED`, and `REVIEW_REQUIRED` must not collapse into one label.
- A route without target-market coverage must not receive a zero score or participate in ranking.
- Java deterministic rules own hard eligibility; LLM output cannot override them.
- No automatic application, CAPTCHA bypass, login bypass, or fabricated future dates.
- Production code must be preceded by a failing test and a verified RED/GREEN cycle.

---

### Task 1: Model structured graduate eligibility rules

**Files:**
- Create: `career-domain/src/main/java/com/careeros/domain/GraduateEligibilityRule.java`
- Test: `career-domain/src/test/java/com/careeros/domain/GraduateEligibilityRuleTest.java`

**Interfaces:**
- Produces: `GraduateEligibilityRule`, `CohortScope`, `EvidenceState`, and `RequirementTiming` for parsing, persistence, and planning.
- Consumes: no new production interface.

- [ ] **Step 1: Write the failing domain tests**

```java
@Test
void convertsExplicitYearsIntoRelativeCohortsForTheRecruitmentYear() {
    var rule = GraduateEligibilityRule.fromExplicitYears(
        2026, Set.of(2024, 2025, 2026), true,
        "2024年、2025年和2026年普通高校毕业生，含同期留学回国人员");

    assertThat(rule.cohorts()).containsExactlyInAnyOrder(
        CohortScope.CURRENT_YEAR, CohortScope.PREVIOUS_YEAR, CohortScope.TWO_YEARS_PRIOR);
    assertThat(rule.includesOverseasGraduates()).isTrue();
    assertThat(rule.acceptedYearsFor(2027)).containsExactlyInAnyOrder(2025, 2026, 2027);
}

@Test
void refusesToProjectNonContiguousOrFutureYears() {
    var rule = GraduateEligibilityRule.fromExplicitYears(
        2026, Set.of(2023, 2026, 2028), false, "2023、2026、2028届");

    assertThat(rule.evidenceState()).isEqualTo(EvidenceState.REVIEW_REQUIRED);
    assertThat(rule.acceptedYearsFor(2027)).isEmpty();
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run: `./mvnw -pl career-domain -Dtest=GraduateEligibilityRuleTest test`

Expected: compilation failure because `GraduateEligibilityRule` does not exist.

- [ ] **Step 3: Implement the minimal immutable domain model**

```java
public record GraduateEligibilityRule(
    int recruitmentYear,
    Set<Integer> explicitGraduationYears,
    Set<CohortScope> cohorts,
    boolean includesOverseasGraduates,
    RequirementTiming degreeTiming,
    LocalDate degreeDeadline,
    RequirementTiming credentialTiming,
    LocalDate credentialDeadline,
    boolean requiresNoEmployer,
    boolean restrictsSocialInsurance,
    String rawText,
    EvidenceState evidenceState
) {
    public Set<Integer> acceptedYearsFor(int targetYear) {
        if (evidenceState != EvidenceState.CONFIRMED || cohorts.contains(CohortScope.UNRESTRICTED)) {
            return Set.of();
        }
        return cohorts.stream().map(scope -> switch (scope) {
            case CURRENT_YEAR -> targetYear;
            case PREVIOUS_YEAR -> targetYear - 1;
            case TWO_YEARS_PRIOR -> targetYear - 2;
            case UNRESTRICTED -> throw new IllegalStateException("handled above");
        }).collect(Collectors.toUnmodifiableSet());
    }
    public static GraduateEligibilityRule fromExplicitYears(
        int recruitmentYear, Set<Integer> years, boolean overseas, String rawText) {
        var offsets = years.stream().map(year -> recruitmentYear - year).collect(Collectors.toSet());
        boolean projectable = offsets.stream().allMatch(offset -> offset >= 0 && offset <= 2);
        var scopes = projectable ? offsets.stream().map(offset -> switch (offset) {
            case 0 -> CohortScope.CURRENT_YEAR;
            case 1 -> CohortScope.PREVIOUS_YEAR;
            case 2 -> CohortScope.TWO_YEARS_PRIOR;
            default -> throw new IllegalStateException("validated above");
        }).collect(Collectors.toUnmodifiableSet()) : Set.<CohortScope>of();
        return new GraduateEligibilityRule(recruitmentYear, years, scopes, overseas,
            RequirementTiming.UNSPECIFIED, null, RequirementTiming.UNSPECIFIED, null,
            false, false, rawText,
            projectable ? EvidenceState.CONFIRMED : EvidenceState.REVIEW_REQUIRED);
    }
}
```

Enums:

```java
public enum CohortScope { CURRENT_YEAR, PREVIOUS_YEAR, TWO_YEARS_PRIOR, UNRESTRICTED }
public enum EvidenceState { CONFIRMED, NOT_PUBLISHED, NOT_REQUIRED, NOT_COLLECTED, PARSE_FAILED, REVIEW_REQUIRED, UNKNOWN }
public enum RequirementTiming { APPLICATION, QUALIFICATION_REVIEW, APPOINTMENT, REPORTING, UNSPECIFIED, NOT_REQUIRED }
```

- [ ] **Step 4: Run the domain tests and verify GREEN**

Run: `./mvnw -pl career-domain -Dtest=GraduateEligibilityRuleTest test`

Expected: all `GraduateEligibilityRuleTest` tests pass.

- [ ] **Step 5: Run the full domain module and commit**

Run: `./mvnw -pl career-domain test`

Commit: `feat: model graduate cohort eligibility rules`

---

### Task 2: Parse graduate, overseas, and process evidence from official notices

**Files:**
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/OfficialAnnouncementFactParser.java`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/OfficialAnnouncementFactParserTest.java`
- Add fixture: `career-infrastructure/src/test/resources/fixtures/acquisition/hangzhou-2026-graduate-and-exam-notice.html`

**Interfaces:**
- Consumes: `GraduateEligibilityRule` from Task 1.
- Produces: `OfficialAnnouncementFacts.graduateEligibilityRule()`, explicit process evidence states, and parsed exam/interview facts.

- [ ] **Step 1: Add a failing real-fixture parser test**

```java
@Test
void parsesRelativeGraduateRuleAndCompleteExamProcess() {
    var html = fixture("hangzhou-2026-graduate-and-exam-notice.html");
    var facts = parser.parse(html, "https://hrss.hangzhou.gov.cn/example");

    assertThat(facts.graduateEligibilityRule().cohorts()).contains(
        CURRENT_YEAR, PREVIOUS_YEAR, TWO_YEARS_PRIOR);
    assertThat(facts.graduateEligibilityRule().includesOverseasGraduates()).isTrue();
    assertThat(facts.graduateEligibilityRule().degreeDeadline()).isEqualTo(LocalDate.of(2026, 9, 30));
    assertThat(facts.writtenExamState()).isEqualTo(CONFIRMED);
    assertThat(facts.writtenExamOn()).isEqualTo(LocalDate.of(2026, 4, 25));
    assertThat(facts.writtenExamSubjects()).containsExactlyInAnyOrder("职业能力倾向测验", "综合应用能力");
    assertThat(facts.interviewState()).isEqualTo(NOT_PUBLISHED);
}
```

- [ ] **Step 2: Run the parser test and verify RED**

Run: `./mvnw -pl career-infrastructure -Dtest=OfficialAnnouncementFactParserTest test`

Expected: compilation failures for the new structured facts.

- [ ] **Step 3: Extend `OfficialAnnouncementFacts` and deterministic parsing**

Add fields:

```java
GraduateEligibilityRule graduateEligibilityRule,
EvidenceState writtenExamState,
EvidenceState professionalTestState,
EvidenceState interviewState,
LocalDate interviewOn,
String interviewMethod,
String scoreFormula
```

Parse only explicit text. Map “另行通知” to `NOT_PUBLISHED`, explicit “不组织笔试” to `NOT_REQUIRED`, missing sections to `NOT_COLLECTED`, and ambiguous conflicting clauses to `REVIEW_REQUIRED`.

- [ ] **Step 4: Run the parser test and verify GREEN**

Run: `./mvnw -pl career-infrastructure -Dtest=OfficialAnnouncementFactParserTest test`

Expected: parser tests pass and existing registration/exam assertions remain green.

- [ ] **Step 5: Commit the parser slice**

Commit: `feat: parse graduate and exam process evidence`

---

### Task 3: Persist structured graduate and process facts without losing raw evidence

**Files:**
- Create: `career-infrastructure/src/main/resources/db/migration/V25__graduate_and_process_facts.sql`
- Modify: `career-domain/src/main/java/com/careeros/domain/RecruitmentEvent.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaModels.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/OfficialAnnouncementFactService.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/PersistenceAdaptersConfiguration.java`
- Modify: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/OfficialAnnouncementFactServiceTest.java`

**Interfaces:**
- Consumes: structured facts from Task 2.
- Produces: persisted event facts and a backward-compatible `RecruitmentEvent` domain record.

- [ ] **Step 1: Write failing persistence tests**

```java
@Test
void savesStructuredRuleAndDistinguishesNotPublishedFromNotCollected() {
    service.apply(eventId, parsedFacts());
    var saved = events.findById(eventId).orElseThrow();

    assertThat(saved.graduateRuleJson).contains("CURRENT_YEAR");
    assertThat(saved.writtenExamState).isEqualTo("CONFIRMED");
    assertThat(saved.interviewState).isEqualTo("NOT_PUBLISHED");
    assertThat(saved.graduateRule).contains("同期毕业的留学回国人员");
}
```

- [ ] **Step 2: Run persistence tests and verify RED**

Run: `./mvnw -pl career-infrastructure -Dtest=OfficialAnnouncementFactServiceTest test`

Expected: missing columns/fields cause compilation or assertion failure.

- [ ] **Step 3: Add the Flyway migration**

```sql
ALTER TABLE recruitment_event
  ADD COLUMN graduate_rule_json JSONB,
  ADD COLUMN written_exam_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN professional_test_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN interview_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN interview_on DATE,
  ADD COLUMN interview_method TEXT,
  ADD COLUMN score_formula TEXT;

ALTER TABLE recruitment_event
  ADD CONSTRAINT ck_recruitment_event_written_exam_state CHECK
    (written_exam_state IN ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN'));
```

Add these two explicit checks and do not delete the existing raw `graduate_rule`, `overseas_degree_rule`, or `interview_rule` columns:

```sql
ALTER TABLE recruitment_event
  ADD CONSTRAINT ck_recruitment_event_professional_test_state CHECK
    (professional_test_state IN ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN')),
  ADD CONSTRAINT ck_recruitment_event_interview_state CHECK
    (interview_state IN ('CONFIRMED','NOT_PUBLISHED','NOT_REQUIRED','NOT_COLLECTED','PARSE_FAILED','REVIEW_REQUIRED','UNKNOWN'));
```

- [ ] **Step 4: Map the new fields through JPA and domain adapters**

Use `@JdbcTypeCode(SqlTypes.JSON)` for the JSON rule and preserve existing constructors with defaults so current API tests compile.

- [ ] **Step 5: Run persistence tests and verify GREEN**

Run: `./mvnw -pl career-infrastructure -Dtest=OfficialAnnouncementFactServiceTest,OfficialExcelImportServiceTest test`

Expected: both suites pass and workbook imports retain announcement facts.

- [ ] **Step 6: Commit the persistence slice**

Commit: `feat: persist graduate and recruitment process facts`

---

### Task 4: Project historical cohort rules into the target year

**Files:**
- Create: `career-application/src/main/java/com/careeros/application/planning/GraduateEligibilityProjector.java`
- Create: `career-application/src/test/java/com/careeros/application/planning/GraduateEligibilityProjectorTest.java`
- Modify: `career-application/src/main/java/com/careeros/application/planning/CareerPlanPorts.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JdbcCareerPlanQueryAdapter.java`

**Interfaces:**
- Consumes: `GraduateEligibilityRule`, candidate education records, target year, and evaluation mode.
- Produces: `GraduateTrackAssessment assess(CandidateProfile, CandidateFacts, GraduateEligibilityRule, EvaluationMode, int targetYear)`.

- [ ] **Step 1: Write failing projection tests for the real user scenario**

```java
@Test
void keepsHistoricalTruthButTreats2027GraduateAsCurrentCohortIn2027Analog() {
    var actual = projector.assess(candidateExpectedIn2027(), confirmedFacts(), rule2026(),
        HISTORICAL_ACTUAL, 2027);
    var analog = projector.assess(candidateExpectedIn2027(), confirmedFacts(), rule2026(),
        TARGET_YEAR_ANALOG, 2027);

    assertThat(actual.outcome()).isEqualTo(INELIGIBLE);
    assertThat(analog.track()).isEqualTo(TARGET_YEAR_GRADUATE);
    assertThat(analog.outcome()).isEqualTo(CONDITIONALLY_ELIGIBLE);
    assertThat(analog.reasons()).contains("2027 届属于目标年度当届毕业生范围");
}

@Test
void priorEmploymentDoesNotEraseCurrentCohortWithoutAnExplicitNoEmploymentRule() {
    var assessment = projector.assess(candidateWithPriorEmployment(), confirmedFacts(),
        ruleWithoutEmploymentRestriction(), TARGET_YEAR_ANALOG, 2027);
    assertThat(assessment.outcome()).isNotEqualTo(INELIGIBLE);
}
```

- [ ] **Step 2: Run the projector test and verify RED**

Run: `./mvnw -pl career-application -am -Dtest=GraduateEligibilityProjectorTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation failure because projector types do not exist.

- [ ] **Step 3: Implement the projector**

```java
enum EvaluationMode { HISTORICAL_ACTUAL, TARGET_YEAR_ANALOG }
enum GraduateTrack { TARGET_YEAR_GRADUATE, RECENT_GRADUATE_WINDOW, NOT_IN_GRADUATE_SCOPE, CONDITIONAL, UNKNOWN }
record GraduateTrackAssessment(GraduateTrack track, QualificationOutcome outcome, List<String> reasons) {}
```

For `TARGET_YEAR_ANALOG`, call `rule.acceptedYearsFor(targetYear)`. Evaluate no-employer/social-insurance restrictions only when the structured rule explicitly requires them; prior employment before the new degree does not itself produce a failure.

- [ ] **Step 4: Extend the planning query projection**

Select and map `event.graduate_rule_json`, raw graduate rule, credential deadline fields, and process evidence states into `HistoricalJob` without removing current constructor overloads.

- [ ] **Step 5: Run projector and query adapter tests and verify GREEN**

Run: `./mvnw -pl career-application,career-infrastructure -am -Dtest=GraduateEligibilityProjectorTest,JdbcCareerPlanQueryAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: projection and JDBC mapping tests pass.

- [ ] **Step 6: Commit the projection slice**

Commit: `feat: project graduate rules into target year`

---

### Task 5: Replace the three-stage planner with education stage plus graduate track

**Files:**
- Modify: `career-application/src/main/java/com/careeros/application/planning/CareerPlan.java`
- Modify: `career-application/src/main/java/com/careeros/application/planning/CareerPlanService.java`
- Modify: `career-application/src/test/java/com/careeros/application/planning/CareerPlanServiceTest.java`
- Modify: `career-web/src/test/java/com/careeros/CareerPlanApiTest.java`

**Interfaces:**
- Consumes: projector from Task 4.
- Produces: `educationScenarios`, `graduateTrack`, historical actual outcome, target-year analog outcome, and algorithm version `career-plan-v3`.

- [ ] **Step 1: Write failing service tests**

```java
@Test
void labelsTheCurrentEducationStageAsOverseasMasterInProgress() {
    var plan = service.generate(CANDIDATE_ID, 2027, LocalDate.of(2026, 8, 24));
    assertThat(plan.currentScenario().code()).isEqualTo("MASTER_IN_PROGRESS");
    assertThat(plan.currentScenario().label()).isEqualTo("境外硕士在读");
    assertThat(plan.graduateTrack().code()).isEqualTo("TARGET_YEAR_GRADUATE");
}

@Test
void representativeJobExposesActualAndTargetYearAnalogOutcomes() {
    var job = representative(plan);
    assertThat(job.historicalActual().outcome()).isEqualTo(INELIGIBLE);
    assertThat(job.targetYearAnalog().outcome()).isEqualTo(CONDITIONALLY_ELIGIBLE);
}
```

- [ ] **Step 2: Run service tests and verify RED**

Run: `./mvnw -pl career-application -Dtest=CareerPlanServiceTest test`

Expected: assertions fail because the current label is “本科阶段” and analog outcomes are absent.

- [ ] **Step 3: Update the plan contract and evaluation flow**

Add:

```java
public record GraduateTrackSummary(String code, String label, String detail, QualificationOutcome outcome) {}
public record ProjectedJobOutcome(JobScenarioOutcome historicalActual, JobScenarioOutcome targetYearAnalog) {}
```

Use `MASTER_IN_PROGRESS`, `DEGREE_PENDING_VERIFICATION`, and `MASTER_VERIFIED` as education stages. Keep JSON compatibility by retaining `currentScenario`/`futureScenarios`, but emit the corrected code and labels. Do not compare expected master year directly with historical absolute years in analog mode.

- [ ] **Step 4: Update route scoring and absence handling**

Score readiness from `targetYearAnalog`, not historical actual. Add `RouteRankingState` with `RANKED`, `LIMITED`, `NOT_COVERED`, `NO_TARGET_RECORDS`, and `DATA_FAILURE`; non-ranked routes expose `priorityScore: null` through a nullable `Integer`.

- [ ] **Step 5: Run service and API tests and verify GREEN**

Run: `./mvnw -pl career-application,career-web -am -Dtest=CareerPlanServiceTest,CareerPlanApiTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: service and serialized API contract tests pass.

- [ ] **Step 6: Commit the planner correction**

Commit: `fix: evaluate 2027 overseas graduate track correctly`

---

### Task 6: Add complete exam-pattern and recruitment-process aggregates

**Files:**
- Modify: `career-application/src/main/java/com/careeros/application/planning/CareerPlan.java`
- Modify: `career-application/src/main/java/com/careeros/application/planning/CareerPlanPorts.java`
- Modify: `career-application/src/main/java/com/careeros/application/planning/CareerPlanService.java`
- Modify: `career-application/src/test/java/com/careeros/application/planning/CareerPlanServiceTest.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JdbcCareerPlanQueryAdapter.java`

**Interfaces:**
- Produces: `ExamSummary`, `ProcessWindow`, and counts with explicit denominators and missing counts.

- [ ] **Step 1: Write failing aggregate tests**

```java
@Test
void examSummaryCountsEventsAndKeepsUnknownSeparate() {
    var summary = service.generate(CANDIDATE_ID, 2027, AS_OF).examSummary();
    assertThat(summary.totalEvents()).isEqualTo(3);
    assertThat(summary.writtenExamConfirmed()).isEqualTo(1);
    assertThat(summary.writtenExamNotRequired()).isEqualTo(1);
    assertThat(summary.writtenExamUnknown()).isEqualTo(1);
    assertThat(summary.subjects()).contains(new ExamPattern("职业能力倾向测验", 1));
}
```

- [ ] **Step 2: Run service tests and verify RED**

Run: `./mvnw -pl career-application -Dtest=CareerPlanServiceTest test`

Expected: `examSummary` is missing.

- [ ] **Step 3: Implement event-deduplicated aggregates**

```java
public record ExamSummary(
    int totalEvents, int writtenExamConfirmed, int writtenExamNotRequired,
    int writtenExamNotPublished, int writtenExamUnknown,
    int professionalTestConfirmed, int interviewConfirmed,
    List<ExamPattern> subjects, List<ExamPattern> interviewMethods
) {}
```

Use one row per `eventId`; compute application-to-written-exam intervals only when both dates exist. Keep month counts for notice, application, written exam, and interview separately.

- [ ] **Step 4: Map process fields in `JdbcCareerPlanQueryAdapter`**

Extend SQL and `HistoricalJob` with process evidence states, `interviewOn`, `interviewMethod`, and `scoreFormula`.

- [ ] **Step 5: Run service and adapter tests and verify GREEN**

Run: `./mvnw -pl career-application,career-infrastructure -am -Dtest=CareerPlanServiceTest,JdbcCareerPlanQueryAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: aggregates and query projections pass.

- [ ] **Step 6: Commit exam aggregates**

Commit: `feat: expose complete historical exam patterns`

---

### Task 7: Add a target-market source catalog and three-layer coverage model

**Files:**
- Create: `career-domain/src/main/java/com/careeros/domain/acquisition/TargetSource.java`
- Create: `career-infrastructure/src/main/resources/db/migration/V26__target_source_catalog.sql`
- Modify: `career-application/src/main/java/com/careeros/application/planning/CareerPlanPorts.java`
- Modify: `career-application/src/main/java/com/careeros/application/planning/CareerPlan.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JdbcCareerPlanQueryAdapter.java`
- Test: `career-domain/src/test/java/com/careeros/domain/acquisition/TargetSourceTest.java`
- Modify test: `career-infrastructure/src/test/java/com/careeros/infrastructure/persistence/JdbcCareerPlanQueryAdapterTest.java`

**Interfaces:**
- Produces: `ConfiguredCoverage`, `TargetMarketCoverage`, `AnalysisCoverage`, and route-specific source coverage.

- [ ] **Step 1: Write failing coverage tests**

```java
@Test
void notConnectedTargetSourcesPreventMarketCompleteConclusion() {
    var target = new TargetSource("HZ_DATA_GROUP", "杭州数据集团", GOVERNMENT_SOE_DIGITAL,
        "杭州", OFFICIAL_ORGANIZATION, NOT_CONNECTED, "https://www.hzdata.com.cn/");
    assertThat(target.supportsAbsenceConclusion()).isFalse();
}
```

Adapter test:

```java
assertThat(data.targetSources()).hasSizeGreaterThanOrEqualTo(20);
assertThat(data.targetSources()).anyMatch(source -> source.routeCode().equals("RESEARCH_SUPPORT")
    && source.connectionStatus() == NOT_CONNECTED);
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./mvnw -pl career-domain,career-infrastructure -am -Dtest=TargetSourceTest,JdbcCareerPlanQueryAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: target source types/table are missing.

- [ ] **Step 3: Create catalog schema and seed the approved first 20 targets**

Schema:

```sql
CREATE TABLE target_source_catalog (
  code VARCHAR(100) PRIMARY KEY,
  name VARCHAR(300) NOT NULL,
  route_code VARCHAR(64) NOT NULL,
  organization_type VARCHAR(64) NOT NULL,
  region VARCHAR(100) NOT NULL,
  authority_level VARCHAR(32) NOT NULL,
  official_root_url TEXT NOT NULL,
  connection_status VARCHAR(32) NOT NULL,
  recruitment_source_id UUID REFERENCES recruitment_source(id),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Seed these 20 target identities; connection status must reflect reality rather than pretending catalog entries are connected:

```text
ZJ_HRSS_INSTITUTION, HZ_HRSS_INSTITUTION,
HZ_BINJIANG_GOV, HZ_YUHANG_GOV, HZ_QIANTANG_GOV, HZ_XIAOSHAN_GOV, HZ_LINPING_GOV,
HDU_RECRUITMENT, ZJUT_RECRUITMENT, ZJGSU_RECRUITMENT, HZNU_RECRUITMENT,
HZ_FIRST_HOSPITAL, HZ_CHILDRENS_HOSPITAL, HZ_XIXI_HOSPITAL, HZ_TCM_HOSPITAL,
UCAS_HANGZHOU, WESTLAKE_RESEARCH,
HZ_DATA_GROUP, HZ_CAPITAL_GROUP, HZ_METRO_GROUP
```

Use this initial root-domain inventory in the migration. Task 8 audits every value before any source becomes connected:

```text
ZJ_HRSS_INSTITUTION  https://rlsbt.zj.gov.cn/
HZ_HRSS_INSTITUTION  https://hrss.hangzhou.gov.cn/
HZ_BINJIANG_GOV      https://www.hhtz.gov.cn/
HZ_YUHANG_GOV        https://www.yuhang.gov.cn/
HZ_QIANTANG_GOV      https://www.qiantang.gov.cn/
HZ_XIAOSHAN_GOV      https://www.xiaoshan.gov.cn/
HZ_LINPING_GOV       https://www.linping.gov.cn/
HDU_RECRUITMENT      https://renshi.hdu.edu.cn/
ZJUT_RECRUITMENT     https://www.zjut.edu.cn/
ZJGSU_RECRUITMENT    https://www.zjgsu.edu.cn/
HZNU_RECRUITMENT     https://www.hznu.edu.cn/
HZ_FIRST_HOSPITAL    https://zp.hz-hospital.com/
HZ_CHILDRENS_HOSPITAL https://www.hzch.org.cn/
HZ_XIXI_HOSPITAL     https://www.xixih.net/
HZ_TCM_HOSPITAL      https://www.hztcm.net/
UCAS_HANGZHOU        https://hias.ucas.ac.cn/
WESTLAKE_RESEARCH    https://www.westlake.edu.cn/
HZ_DATA_GROUP        https://hr.hzfi.cn/
HZ_CAPITAL_GROUP     https://hr.hzfi.cn/
HZ_METRO_GROUP       https://www.hzmetro.com/
```

Mark only the existing two recruitment sources `CONNECTED` in V26. All other catalog targets start `NOT_CONNECTED`; Task 8 changes status only after validating official identity, listing URL, and deterministic discovery.

- [ ] **Step 4: Implement three coverage records**

```java
record ConfiguredCoverage(boolean complete, int sourceYearCount, int completeSourceYearCount, List<String> gaps) {}
record TargetMarketCoverage(int targetCount, int connected, int partial, int failed, int notConnected, List<RouteCoverage> routes) {}
record AnalysisCoverage(int sourceCount, int eventCount, int jobCount, int evidenceCompleteJobs, Instant loadedAt) {}
```

Keep legacy `dataCoverage.complete` during one compatibility version but label it `configuredSourcesComplete` in explanations.

- [ ] **Step 5: Run coverage tests and verify GREEN**

Run: `./mvnw -pl career-domain,career-infrastructure -am -Dtest=TargetSourceTest,JdbcCareerPlanQueryAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: catalog and coverage projections pass.

- [ ] **Step 6: Commit catalog and coverage model**

Commit: `feat: distinguish target market and configured coverage`

---

### Task 8: Connect and audit the first 20 official source targets

**Files:**
- Create: `career-infrastructure/src/main/resources/official-source-catalog.yml`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/OfficialSourceCatalog.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/StaticHtmlSourceDiscoverer.java`
- Create: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/OfficialSourceCatalogTest.java`
- Modify: `career-web/src/test/java/com/careeros/OfficialSourceLiveSmokeTest.java`
- Create: `scripts/audit_official_sources.ps1`

**Interfaces:**
- Consumes: target identities from Task 7.
- Produces: validated discovery configuration, source audit report, and accurate connection statuses.

- [ ] **Step 1: Write a failing catalog contract test**

```java
@Test
void catalogContainsTwentyUniqueOfficialTargetsAcrossAllRoutes() {
    var catalog = OfficialSourceCatalog.load();
    assertThat(catalog.sources()).hasSizeGreaterThanOrEqualTo(20);
    assertThat(catalog.sources()).extracting(SourceDefinition::code).doesNotHaveDuplicates();
    assertThat(catalog.sources()).extracting(SourceDefinition::routeCode)
        .contains("PUBLIC_TECH", "UNIVERSITY_HOSPITAL_IT", "RESEARCH_SUPPORT", "GOVERNMENT_SOE_DIGITAL");
    assertThat(catalog.sources()).allMatch(source -> URI.create(source.officialRootUrl()).getScheme().equals("https"));
}
```

- [ ] **Step 2: Run the catalog test and verify RED**

Run: `./mvnw -pl career-infrastructure -Dtest=OfficialSourceCatalogTest test`

Expected: catalog loader/config are missing.

- [ ] **Step 3: Add source definitions and deterministic discovery strategies**

Each YAML item must provide:

```yaml
- code: HZ_HRSS_INSTITUTION
  name: 杭州市人力资源和社会保障局事业单位招聘
  routeCode: PUBLIC_TECH
  officialRootUrl: https://hrss.hangzhou.gov.cn/
  listingUrl: https://hrss.hangzhou.gov.cn/col/col1229782005/index.html
  strategy: JCMS_LISTING
  historicalYears: [2024, 2025, 2026]
  enabled: true
```

For every target, verify the official identity and current recruitment/listing URL before marking it `enabled: true`. Unsupported dynamic or blocked targets stay in the target catalog as `NOT_CONNECTED`; never invent a successful connector.

- [ ] **Step 4: Add the read-only audit script**

The script must resolve each configured official URL, record HTTP status/final host/content type, reject cross-domain redirects, and emit JSON without mutating the database:

Run: `powershell -File scripts/audit_official_sources.ps1 -OutputPath target/source-audit.json`

Expected: output contains one record per target and returns nonzero only for malformed catalog definitions; individual inaccessible sites are recorded as `ACCESS_FAILED`.

- [ ] **Step 5: Run deterministic tests and the live audit**

Run: `./mvnw -pl career-infrastructure -Dtest=OfficialSourceCatalogTest,StaticHtmlSourceDiscovererTest test`

Run: `powershell -File scripts/audit_official_sources.ps1 -OutputPath target/source-audit.json`

Expected: unit tests pass; audit truthfully distinguishes connected, redirected, blocked, and inaccessible sources.

- [ ] **Step 6: Update connection statuses from audit evidence and commit**

Only audited, parseable sources become `CONNECTED` or `PARTIAL`. Catalog entries without a working deterministic path remain `NOT_CONNECTED` and therefore suppress route ranking.

Commit: `feat: register and audit official target sources`

---

### Task 9: Return the corrected planning contract through the API

**Files:**
- Modify: `career-web/src/test/java/com/careeros/CareerPlanApiTest.java`
- Modify: `career-web/src/test/java/com/careeros/CareerPlanEndToEndTest.java`
- Inspect only: `career-web/src/main/java/com/careeros/CareerPlanController.java` (the record is serialized directly; no controller edit is planned)
- Modify: `docs/PHASE5A_CAREER_PLANNER.md`

**Interfaces:**
- Consumes: plan v3 from Tasks 5–8.
- Produces: backward-compatible JSON with graduate track, analog outcomes, exam summary, and three-layer coverage.

- [ ] **Step 1: Write failing API assertions**

```java
mockMvc.perform(get("/api/v1/candidates/{id}/career-plan", candidateId)
        .param("targetYear", "2027").param("asOf", "2026-08-24"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.currentScenario.code").value("MASTER_IN_PROGRESS"))
    .andExpect(jsonPath("$.graduateTrack.code").value("TARGET_YEAR_GRADUATE"))
    .andExpect(jsonPath("$.examSummary.totalEvents").isNumber())
    .andExpect(jsonPath("$.dataCoverage.targetMarketCoverage.targetCount").value(20))
    .andExpect(jsonPath("$.algorithmVersion").value("career-plan-v3"));
```

- [ ] **Step 2: Run API tests and verify RED**

Run: `./mvnw -pl career-web -Dtest=CareerPlanApiTest,CareerPlanEndToEndTest test`

Expected: new JSON paths are missing.

- [ ] **Step 3: Complete API serialization and compatibility documentation**

Keep existing fields for current clients, document nullable route scores and ranking states, and state that `configuredCoverage.complete` does not mean market complete.

- [ ] **Step 4: Run API tests and verify GREEN**

Run: `./mvnw -pl career-web -Dtest=CareerPlanApiTest,CareerPlanEndToEndTest test`

Expected: both API suites pass.

- [ ] **Step 5: Commit the API contract**

Commit: `feat: expose graduate track and market coverage`

---

### Task 10: Redesign the planning page around the user's two application channels

**Files:**
- Modify: `career-ui/src/features/planning/planningApi.ts`
- Modify: `career-ui/src/features/planning/CareerPlanPage.tsx`
- Modify: `career-ui/src/features/planning/CareerPlanPage.test.tsx`
- Modify: `career-ui/src/styles/planning.css`

**Interfaces:**
- Consumes: Career Plan v3 API.
- Produces: visible 2027 overseas fresh-graduate conclusion, fresh/social dual tracks, ranking-state-aware route cards, and explicit market coverage.

- [ ] **Step 1: Write failing UI tests**

```tsx
expect(await screen.findByText('2027 届境外硕士应届生候选')).toBeVisible()
expect(screen.getByRole('heading', { name: '应届通道' })).toBeVisible()
expect(screen.getByRole('heading', { name: '社会人员通道' })).toBeVisible()
expect(screen.getByText('境外硕士在读')).toBeVisible()
expect(screen.getByText('尚未接入目标来源，暂不排名')).toBeVisible()
expect(screen.queryByText('路线 4')).not.toBeInTheDocument()
```

- [ ] **Step 2: Run the planning page test and verify RED**

Run: `npm test -- CareerPlanPage.test.tsx`

Workdir: `career-ui`

Expected: fresh-graduate and coverage labels are absent.

- [ ] **Step 3: Update TypeScript contracts and page composition**

Add typed `GraduateTrackSummary`, `ExamSummary`, coverage records, nullable route score, and `RouteRankingState`. Render outcome-first sections in this order: candidate conclusion, two channels, key dates/exams, routes, coverage, evidence.

- [ ] **Step 4: Implement explicit missing-state copy**

Map:

```text
NOT_PUBLISHED -> 官网说明另行通知/尚未发布
NOT_COLLECTED -> 系统尚未采集该通知
PARSE_FAILED -> 已取得官网原件，但自动解析失败
REVIEW_REQUIRED -> 官网规则存在歧义，等待复核
UNKNOWN -> 当前证据无法判断
```

- [ ] **Step 5: Run page tests and verify GREEN**

Run: `npm test -- CareerPlanPage.test.tsx`

Workdir: `career-ui`

Expected: all planning page tests pass.

- [ ] **Step 6: Commit the planning UI**

Commit: `feat: show fresh graduate and social application tracks`

---

### Task 11: Show the complete in-app job process and keep actions useful with an empty opportunity pool

**Files:**
- Modify: `career-ui/src/features/planning/planningApi.ts`
- Modify: `career-ui/src/features/planning/PlanningJobDetailPage.tsx`
- Modify: `career-ui/src/features/planning/PlanningJobDetailPage.test.tsx`
- Modify: `career-application/src/main/java/com/careeros/application/PersonalActionService.java`
- Modify: `career-application/src/test/java/com/careeros/application/PersonalActionServiceTest.java`
- Modify: `career-ui/src/features/today/TodayPage.test.tsx`

**Interfaces:**
- Consumes: process states and projected outcomes.
- Produces: historical/analog decision panels, full process timeline, and baseline evidence/exam actions even when T1/T2/T3 are empty.

- [ ] **Step 1: Write failing job-detail UI tests**

```tsx
expect(await screen.findByRole('heading', { name: '2027 同类岗位推演' })).toBeVisible()
expect(screen.getByText('历史岗位当年：不可报')).toBeVisible()
expect(screen.getByText('2027 同类岗位：条件可报')).toBeVisible()
expect(screen.getByText('笔试：2026-04-25')).toBeVisible()
expect(screen.getByText('面试时间：官网说明另行通知')).toBeVisible()
```

- [ ] **Step 2: Write a failing baseline-action service test**

```java
@Test
void emitsProfileAndExamPreparationActionsWhenTrustedPoolIsEmpty() {
    var actions = service.generate(candidateId, LocalDate.of(2026, 8, 24));
    assertThat(actions.items()).extracting(PersonalAction::code)
        .contains("CONFIRM_MASTER_GRADUATION_MONTH", "VERIFY_MASTER_CREDENTIAL", "PREPARE_WRITTEN_EXAM_BASELINE");
}
```

- [ ] **Step 3: Run UI and service tests and verify RED**

Run: `npm test -- PlanningJobDetailPage.test.tsx TodayPage.test.tsx` in `career-ui`.

Run: `./mvnw -pl career-application -Dtest=PersonalActionServiceTest test` from repository root.

Expected: projected panel and baseline actions are absent.

- [ ] **Step 4: Implement the detail timeline and baseline actions**

Display announcement, registration, review, payment, admission ticket, written exam, professional test, interview, physical exam/publication/employment text, each with its evidence state. Generate baseline actions from candidate evidence tasks and historical exam patterns independently of trusted opportunities; never create application/deadline actions without a live official event.

- [ ] **Step 5: Run affected tests and verify GREEN**

Run: `./mvnw -pl career-application -Dtest=PersonalActionServiceTest test`

Run: `npm test -- PlanningJobDetailPage.test.tsx TodayPage.test.tsx` in `career-ui`.

Expected: all affected tests pass.

- [ ] **Step 6: Commit the user workflow**

Commit: `feat: expose full recruitment workflow and baseline actions`

---

### Task 12: Migrate real data, verify the user's result, and complete browser acceptance

**Files:**
- Modify: `scripts/planning_browser_acceptance.py`
- Create: `scripts/career_correctness_acceptance.ps1`
- Modify: `README.md`
- Modify: `docs/current-gap-analysis.md`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: repeatable end-to-end evidence for the real candidate and honest remaining source gaps.

- [ ] **Step 1: Add failing acceptance assertions before changing data**

The acceptance script must assert API values for candidate `01992f09-0000-7000-8000-000000000001`:

```powershell
$plan.currentScenario.code | Should -Be 'MASTER_IN_PROGRESS'
$plan.graduateTrack.code | Should -Be 'TARGET_YEAR_GRADUATE'
$plan.targetYear | Should -Be 2027
$plan.dataCoverage.targetMarketCoverage.targetCount | Should -BeGreaterOrEqual 20
($plan.recommendedRoutes | Where-Object rankingState -eq 'NOT_COVERED').priorityScore | Should -BeNullOrEmpty
```

Also assert that at least one 2026 representative job has distinct historical actual and 2027 analog results and that exam subjects include `职业能力倾向测验` and `综合应用能力` when supported by official evidence.

- [ ] **Step 2: Run acceptance and verify RED**

Run: `powershell -File scripts/career_correctness_acceptance.ps1 -BaseUrl http://localhost:8080`

Expected: current API returns `PRE_GRADUATION`, has no graduate track, and lacks target-market coverage.

- [ ] **Step 3: Apply migrations and reprocess official event facts**

Run the application normally so Flyway applies V25/V26. Re-run the existing official announcement fact fusion/import path for stored 2024–2026 notices; do not edit imported job facts by hand. Record inserted/updated/unchanged counts.

- [ ] **Step 4: Run focused and full backend verification**

Run: `./mvnw test`

Expected: all reactor tests pass with zero failures and zero errors.

- [ ] **Step 5: Run full frontend verification and production build**

Run: `npm run verify && npm run build`

Workdir: `career-ui`

Expected: TypeScript, all Vitest tests, and Vite production build pass.

- [ ] **Step 6: Run API acceptance and real browser acceptance**

Run: `powershell -File scripts/career_correctness_acceptance.ps1 -BaseUrl http://localhost:8080`

Run: `python scripts/planning_browser_acceptance.py`

Expected browser assertions:

```text
规划页显示“2027 届境外硕士应届生候选”
当前阶段显示“境外硕士在读”
应届通道与社会人员通道均可见
无覆盖路线显示“暂不排名”而不是 0 分
代表岗位可查看历史实际与 2027 同类推演
报名、笔试、科目、面试状态和官方链接均在站内可见
今日页在可信池为空时仍有材料与考试准备动作
浏览器控制台 0 error
```

- [ ] **Step 7: Update documentation with measured coverage, not aspirations**

Record exact connected/partial/failed/not-connected source counts, exact verified event/job counts, test counts, and known gaps. Do not write “market complete” unless every target source and required year has supporting coverage evidence.

- [ ] **Step 8: Commit the acceptance evidence**

Commit: `test: verify career decision correctness end to end`

---

## Plan Self-Review Traceability

| Spec requirement | Implementing tasks |
|---|---|
| 2027 overseas fresh-graduate track | 1, 2, 4, 5, 9, 10, 12 |
| Historical actual vs target-year analog | 4, 5, 9, 11, 12 |
| Degree/credential/employment restrictions | 1, 2, 3, 4 |
| Complete recruitment and exam workflow | 2, 3, 6, 9, 11 |
| Explicit missing-information states | 1, 2, 3, 10, 11 |
| Three-layer coverage semantics | 7, 9, 10, 12 |
| First 20 official target sources | 7, 8, 12 |
| Route ranking suspension | 5, 7, 9, 10, 12 |
| Actions when trusted pool is empty | 11, 12 |
| Real-profile API and browser acceptance | 12 |
