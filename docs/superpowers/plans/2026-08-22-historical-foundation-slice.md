# Historical Opportunity Foundation Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first testable foundation for `REQ-PROFILE-001` and `REQ-COVERAGE-001`: preserve completed and expected education separately, and distinguish historical source-year gaps from confirmed no-target years.

**Architecture:** Extend the existing Java domain and hexagonal persistence ports without replacing legacy candidate summary fields or the existing incremental acquisition pipeline. Education records are candidate-owned value rows persisted with the profile; source-year coverage is an acquisition-domain aggregate exposed through the existing acquisition API. UI changes make the two real education records visible and editable.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, PostgreSQL 16, Flyway, JUnit 5, AssertJ, MockMvc, React 19, TypeScript, TanStack Query, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-22-historical-opportunity-forecast-design.md`

## Global Constraints

- Preserve all current API fields and current eligibility behavior until the education-aware eligibility task is implemented.
- `EXPECTED` education must never be silently rewritten as completed education.
- Missing source-year coverage must never be represented as zero target jobs.
- Re-running migrations and saves must not duplicate education or coverage rows.
- Use migration `V18`; never edit an already applied migration.
- Do not expose source selector configuration or candidate private facts in logs.

---

### Task 1: Candidate education value model and fact identity (`REQ-PROFILE-001`)

**Files:**
- Create: `career-domain/src/main/java/com/careeros/domain/EducationRecord.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/CandidateProfile.java`
- Modify: `career-domain/src/main/java/com/careeros/domain/CandidateFacts.java`
- Test: `career-domain/src/test/java/com/careeros/domain/CandidateFactsTest.java`
- Test: `career-domain/src/test/java/com/careeros/domain/EducationRecordTest.java`

**Interfaces:**
- Produces: `EducationRecord`, `CompletionStatus`, `CredentialVerificationStatus`.
- Produces: `CandidateProfile.educationRecords(): List<EducationRecord>`.
- Produces: `CandidateFactKey.EDUCATION_RECORDS` whose fingerprint is order-independent but field-sensitive.

- [ ] **Step 1: Write failing value and fingerprint tests**

```java
@Test void preservesCompletedBachelorAndExpectedMasterSeparately() {
    var records = List.of(
        new EducationRecord(null, null, BACHELOR, "计算机科学与技术", 2014, null,
            COMPLETED, NOT_REQUIRED),
        new EducationRecord("示例海外大学", "示例国", MASTER, "计算机科学", 2027, null,
            EXPECTED, PLANNED));
    assertThat(records).extracting(EducationRecord::completionStatus)
        .containsExactly(COMPLETED, EXPECTED);
}

@Test void educationFingerprintChangesWhenExpectedBecomesCompleted() {
    assertThat(fingerprint(profileWith(EXPECTED), EDUCATION_RECORDS))
        .isNotEqualTo(fingerprint(profileWith(COMPLETED), EDUCATION_RECORDS));
}
```

- [ ] **Step 2: Run RED**

Run: `mvn -pl career-domain -Dtest=EducationRecordTest,CandidateFactsTest test`

Expected: compilation fails because `EducationRecord` and `EDUCATION_RECORDS` do not exist.

- [ ] **Step 3: Implement the minimal domain model**

```java
public record EducationRecord(
    String institutionName,
    String countryOrRegion,
    EducationLevel educationLevel,
    String majorName,
    Integer graduationYear,
    Integer graduationMonth,
    CompletionStatus completionStatus,
    CredentialVerificationStatus credentialVerificationStatus
) {
    public enum CompletionStatus { COMPLETED, EXPECTED }
    public enum CredentialVerificationStatus { NOT_REQUIRED, PLANNED, IN_PROGRESS, VERIFIED, UNKNOWN }
}
```

Append `List<EducationRecord> educationRecords` to the canonical profile constructor; keep compatibility constructors delegating with `List.of()`. Canonicalize the education fingerprint by sorting a length-prefixed encoding of every field.

- [ ] **Step 4: Run GREEN**

Run: `mvn -pl career-domain -Dtest=EducationRecordTest,CandidateFactsTest test`

Expected: PASS.

### Task 2: Education persistence and safe seed migration (`REQ-PROFILE-001`, `CFR-TRUST-001`)

**Files:**
- Create: `career-infrastructure/src/main/resources/db/migration/V18__historical_foundation.sql`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/JpaModels.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/persistence/PersistenceAdaptersConfiguration.java`
- Modify: `career-infrastructure/src/main/resources/db/migration/V12__candidate_fact_confirmations.sql` only by adding no code: V12 is frozen; V18 alters its check constraint.
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/MigrationIntegrationTest.java`
- Test: `career-web/src/test/java/com/careeros/CandidateProfileTransactionIntegrationTest.java`

**Interfaces:**
- Consumes: `CandidateProfile.educationRecords()`.
- Produces: `candidate_education_record(candidate_profile_id, record_order, ...)` with cascade delete and a composite primary key.
- Produces: V18 seed rows for the known candidate: completed bachelor in 2014 and expected Moscow State master in 2027.

- [ ] **Step 1: Write failing migration assertions**

```java
assertThat(migrationCount).isEqualTo(18);
assertThat(tableExists("candidate_education_record")).isTrue();
assertThat(seedEducationStatuses()).containsExactly("COMPLETED", "EXPECTED");
assertThat(candidateFactConstraint()).contains("EDUCATION_RECORDS");
```

- [ ] **Step 2: Run RED**

Run: `mvn -pl career-infrastructure -Dtest=MigrationIntegrationTest test`

Expected: FAIL because V18 and the new table do not exist.

- [ ] **Step 3: Add V18 and JPA element collection mapping**

```sql
CREATE TABLE candidate_education_record (...);
ALTER TABLE candidate_fact_confirmation DROP CONSTRAINT candidate_fact_confirmation_fact_key_check;
ALTER TABLE candidate_fact_confirmation ADD CONSTRAINT candidate_fact_confirmation_fact_key_check
    CHECK (fact_key IN (..., 'EDUCATION_RECORDS'));
```

Map `CandidateProfileEntity.educationRecords` as an eager ordered element collection and copy it in both adapter directions. Use `ON CONFLICT DO NOTHING` for the known candidate seed rows.

- [ ] **Step 4: Run GREEN**

Run: `mvn -pl career-infrastructure,career-web -am -Dtest=MigrationIntegrationTest,CandidateProfileTransactionIntegrationTest test`

Expected: PASS and no duplicate rows after profile update.

### Task 3: Candidate API and non-technical profile UI (`REQ-PROFILE-001`, `REQ-UX-001`)

**Files:**
- Modify: `career-application/src/main/java/com/careeros/application/CandidateProfileService.java`
- Modify: `career-web/src/main/java/com/careeros/CareerMvpController.java`
- Modify: `career-ui/src/features/profile/profileSchema.ts`
- Modify: `career-ui/src/features/profile/ProfileForm.tsx`
- Modify: `career-ui/src/features/profile/ProfilePage.tsx`
- Test: `career-application/src/test/java/com/careeros/application/CandidateProfileServiceTest.java`
- Test: `career-web/src/test/java/com/careeros/CandidateDecisionProfileMappingTest.java`
- Test: `career-ui/src/features/profile/ProfilePage.test.tsx`

**Interfaces:**
- Consumes/produces API field `educationRecords` with the domain field names.
- The legacy summary fields remain accepted for older clients.
- Profile confirmation includes `EDUCATION_RECORDS` and invalidates only that fact when one record changes.

- [ ] **Step 1: Write failing service/API/UI tests**

```typescript
expect(await screen.findByText('本科 · 计算机科学与技术 · 2014年毕业')).toBeInTheDocument()
expect(screen.getByText('硕士 · 计算机科学 · 预计2027年毕业')).toBeInTheDocument()
```

Also assert the PUT body contains both records and never contains `profileVersion`.

- [ ] **Step 2: Run RED**

Run: `mvn -pl career-application,career-web -am -Dtest=CandidateProfileServiceTest,CandidateDecisionProfileMappingTest test`

Run: `npm test -- --run src/features/profile/ProfilePage.test.tsx`

Expected: tests fail because education records are not mapped or rendered.

- [ ] **Step 3: Implement minimal mapping and UI**

```typescript
export type EducationRecord = {
  institutionName: string | null
  countryOrRegion: string | null
  educationLevel: string
  majorName: string
  graduationYear: number | null
  graduationMonth: number | null
  completionStatus: 'COMPLETED' | 'EXPECTED'
  credentialVerificationStatus: 'NOT_REQUIRED' | 'PLANNED' | 'IN_PROGRESS' | 'VERIFIED' | 'UNKNOWN'
}
```

Render two editable education cards. Labels must say “已毕业” or “预计毕业”; do not call the expected master an obtained degree.

- [ ] **Step 4: Run GREEN**

Run the same focused Maven and Vitest commands. Expected: PASS.

### Task 4: Source-year coverage domain and persistence (`REQ-COVERAGE-001`)

**Files:**
- Create: `career-domain/src/main/java/com/careeros/domain/acquisition/SourceYearCoverage.java`
- Modify: `career-application/src/main/java/com/careeros/application/AcquisitionPorts.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/AcquisitionJpaModels.java`
- Create: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/SourceYearCoverageJpaRepository.java`
- Modify: `career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStore.java`
- Modify: `career-infrastructure/src/main/resources/db/migration/V18__historical_foundation.sql`
- Test: `career-domain/src/test/java/com/careeros/domain/acquisition/SourceYearCoverageTest.java`
- Test: `career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStoreTest.java`

**Interfaces:**
- Produces `CoverageStatus`: `NOT_DISCOVERED`, `DISCOVERED_NOT_FETCHED`, `ACCESS_FAILED`, `FETCHED_NOT_PARSED`, `PARTIAL`, `COMPLETE`, `NO_TARGET_RECORDS`.
- Produces `boolean supportsAbsenceConclusion()` true only for `COMPLETE` and `NO_TARGET_RECORDS`.
- Adds `findSourceYearCoverage(UUID sourceId, Integer year)` and `saveSourceYearCoverage(SourceYearCoverage value)` to `AcquisitionStore`.

- [ ] **Step 1: Write failing domain and PostgreSQL tests**

```java
assertThat(coverage(ACCESS_FAILED).supportsAbsenceConclusion()).isFalse();
assertThat(coverage(NO_TARGET_RECORDS).supportsAbsenceConclusion()).isTrue();
assertThat(store.findSourceYearCoverage(SOURCE_ID, 2025)).hasSize(1);
```

- [ ] **Step 2: Run RED**

Run: `mvn -pl career-domain,career-infrastructure -am -Dtest=SourceYearCoverageTest,JpaAcquisitionStoreTest test`

Expected: compilation fails because the aggregate and store methods do not exist.

- [ ] **Step 3: Implement domain, V18 table, repository, and mappings**

```java
public record SourceYearCoverage(
    UUID sourceId, int recruitmentYear, CoverageStatus status,
    int discoveredCount, int fetchedCount, int parsedCount, int targetJobCount,
    String completionBasis, Instant completedAt, Instant updatedAt
) { ... }
```

Seed both configured official sources for 2024–2026 as `NOT_DISCOVERED`; do not seed zero target counts as evidence of absence.

- [ ] **Step 4: Run GREEN**

Run the focused tests again. Expected: PASS.

### Task 5: Coverage read API and semantic regression (`REQ-COVERAGE-001`, `CFR-TRUST-001`)

**Files:**
- Modify: `career-web/src/main/java/com/careeros/AcquisitionApiModels.java`
- Modify: `career-web/src/main/java/com/careeros/AcquisitionController.java`
- Test: `career-web/src/test/java/com/careeros/AcquisitionApiTest.java`

**Interfaces:**
- Produces `GET /api/acquisition/coverage?sourceId={uuid}&year={yyyy}`.
- Response fields include source, year, status, all counts, completion basis, timestamps, and `supportsAbsenceConclusion`.

- [ ] **Step 1: Write failing API test**

```java
mvc.perform(get("/api/acquisition/coverage").param("year", "2025"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$[0].status").value("ACCESS_FAILED"))
    .andExpect(jsonPath("$[0].supportsAbsenceConclusion").value(false));
```

- [ ] **Step 2: Run RED**

Run: `mvn -pl career-web -am -Dtest=AcquisitionApiTest test`

Expected: 404 because the endpoint does not exist.

- [ ] **Step 3: Implement the read endpoint and DTO**

Validate years to `2000..2100`; sort by source ID then year. Do not expose selector configuration.

- [ ] **Step 4: Run GREEN and full verification**

Run: `mvn test`

Run: `npm test -- --run && npm run build`

Expected: all tests and builds pass.

### Task 6: Handoff and traceability

**Files:**
- Modify: this plan, checking completed tasks.
- Modify: `docs/superpowers/specs/2026-08-22-historical-opportunity-forecast-design.md` only if implementation reveals a contract change.

**Interfaces:**
- Produces a commit whose message references the historical foundation slice.

- [ ] **Step 1: Verify traceability and diff quality**

Run: `git diff --check`

Run: `git status --short`

- [ ] **Step 2: Commit and push**

```text
git commit -m "feat: add historical research foundations"
git push origin HEAD
```

