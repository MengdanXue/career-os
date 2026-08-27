# Hangzhou Source Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Connect and live-verify all 28 Hangzhou target official sources, with no NOT_CONNECTED or FAILED source and with conservative, evidence-backed 2024–2026 coverage.

**Architecture:** Extend the existing SourceListingReader into a typed multi-entry orchestrator while preserving its application port and all legacy source behavior. Shared listing modes, audited transport, evidence aggregation, and employment/lifecycle normalization are implemented once; three forward-only Flyway source batches then provide per-site contracts and are accepted through incremental → historical → incremental live runs.

**Tech Stack:** Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway, Jsoup, Jackson, Apache POI, JUnit 5, Testcontainers, React 19, TypeScript 7, Vitest, Playwright CLI.

**Spec:** docs/superpowers/specs/2026-08-27-hangzhou-source-completion-design.md

## Global Constraints

- Default transport is HTTPS; audited HTTP is read-only, exact-host and path-prefix scoped, and never carries credentials or candidate data.
- A year is COMPLETE only when every required listing entry proves terminal traversal and all downloads/parsers succeed.
- A configured source with an unprovable official archive remains PARTIAL; no empty result may be promoted to COMPLETE.
- Existing canonical URL, content fingerprint, stable job key, source pacing, redirect validation, response-size limits, and unique-only lifecycle linking remain intact.
- Runtime configuration is recruitment_source.configuration seeded by Flyway; YAML is a validated catalog mirror.
- Every behavior change follows red-green-refactor TDD and each source batch receives a separate commit and live acceptance record.
- Existing migrations are immutable; all schema and source changes use V43 and later.

---

### Task 1: Typed multi-entry and transport contracts

**Files:**
- Modify: career-application/src/main/java/com/careeros/application/AcquisitionHttpPorts.java
- Create: career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/ListingEntryContract.java
- Create: career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/ListingEntryContractTest.java
- Create: career-application/src/test/java/com/careeros/application/AcquisitionHttpPortsTest.java

**Interfaces:**
- Produces ListingEntryContract.from(RecruitmentSource), ListingEntryEvidence, FetchMethod, TransportPolicy, TransportRisk, and HttpReadContract.
- Preserves SourceListingReader.read and compatibility constructors for ListingResult, FetchRequest, and FetchedDocument.

- [ ] **Step 1: Write failing entry-contract tests**

Tests prove that two distinct entries parse, a legacy mode becomes one synthetic entry, duplicate entry codes fail, plain HTTP fails, and audited HTTP requires exact hosts and nonempty path prefixes.

    assertThat(ListingEntryContract.from(sourceWithTwoEntries()))
        .extracting(ListingEntryContract::code)
        .containsExactly("primary", "lifecycle");
    assertThatThrownBy(() -> ListingEntryContract.from(sourceWithDuplicateEntries()))
        .hasMessageContaining("entry code");
    assertThatThrownBy(() -> ListingEntryContract.from(sourceWithUnscopedHttp()))
        .hasMessageContaining("AUDITED_HTTP_READ_ONLY");

- [ ] **Step 2: Run tests and verify RED**

    $env:JAVA_HOME=(Resolve-Path '.tooling/temurin-21/jdk-21.0.12+8').Path
    mvn -pl career-infrastructure -am '-Dtest=ListingEntryContractTest,AcquisitionHttpPortsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

Expected: compile/test failure because the new records and parser do not exist.

- [ ] **Step 3: Add compatibility-safe application records**

Add ListingEntryEvidence, FetchMethod GET/HEAD, TransportPolicy HTTPS_ONLY/AUDITED_HTTP_READ_ONLY, TransportRisk NONE/PLAINTEXT_OFFICIAL_HTTP, and HttpReadContract. Old constructors delegate to GET, HTTPS_ONLY, and NONE.

- [ ] **Step 4: Implement ListingEntryContract**

Roles are PRIMARY, HISTORICAL, SECTOR, LIFECYCLE, CAMPAIGN_STATE. Modes are JCMS_PARAM_JSON, STATIC_SUFFIX_TEMPLATE, LINKED_PAGE, QUERY_PAGE, JSON_API, EMBEDDED_DATA, CAMPAIGN_STATE, FIXED_EVIDENCE. Parse configuration.listingEntries, inherit source-level defaults, validate years/uniqueness/transport, and synthesize a legacy entry when the array is absent.

- [ ] **Step 5: Verify GREEN and commit**

Run Step 2 and require exit 0.

    git add career-application/src/main/java/com/careeros/application/AcquisitionHttpPorts.java career-application/src/test/java/com/careeros/application/AcquisitionHttpPortsTest.java career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/ListingEntryContract.java career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition/ListingEntryContractTest.java
    git commit -m "feat: define multi-entry source contracts"

---

### Task 2: Multi-entry listing modes and conservative aggregation

**Files:**
- Modify: career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/ConfigurableSourceListingReader.java
- Modify: career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/StaticHtmlSourceDiscoverer.java
- Modify: career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/RoutingSourceDiscoverer.java
- Test: ConfigurableSourceListingReaderTest.java, StaticHtmlSourceDiscovererTest.java, RoutingSourceDiscovererTest.java

**Interfaces:**
- Consumes Task 1 contracts.
- Produces per-entry evidence and annual aggregation requiring every applicable completenessRequired entry to complete.

- [ ] **Step 1: Write failing real-reader tests**

Cover cross-entry canonical deduplication, required-entry incomplete aggregation, News130a{page}.htm templates, query preservation, JSON total reconciliation, embedded data, repeated/empty middle pages, terminal mismatch, cycle, and maximum-page failure.

    assertThat(result.links()).extracting(link -> link.link().uri()).containsExactly(expected);
    assertThat(result.evidenceByYear().get(2025).traversalComplete()).isFalse();
    assertThat(requestedUris).containsExactly(
        URI.create("https://official/News130a1.htm"),
        URI.create("https://official/News130a2.htm"));

- [ ] **Step 2: Verify RED**

    mvn -pl career-infrastructure -am '-Dtest=ConfigurableSourceListingReaderTest,StaticHtmlSourceDiscovererTest,RoutingSourceDiscovererTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

- [ ] **Step 3: Implement the entry orchestrator**

Use private readEntry, aggregate, and EntryResult boundaries. Keep current JCMS/linked safeguards. Implement template, query, JSON, embedded, campaign, and fixed-evidence modes. Legacy STATIC_PAGE_SUFFIX and FIXED_HTTPS_EVIDENCE remain accepted aliases.

- [ ] **Step 4: Apply entry-specific discovery policy**

The active entry supplies article regex and title filters. Preserve legacy overloads and lifecycle-first ordering.

- [ ] **Step 5: Verify GREEN and commit**

    mvn -pl career-infrastructure -am '-Dtest=*Acquisition*Test,*Source*DiscovererTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    git add career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition career-infrastructure/src/test/java/com/careeros/infrastructure/acquisition
    git commit -m "feat: support heterogeneous listing entries"

---

### Task 3: Audited HTTP and transport-risk persistence

**Files:**
- Modify: career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JavaHttpDocumentFetcher.java
- Modify: career-domain/src/main/java/com/careeros/domain/acquisition/FetchObservation.java
- Modify: career-domain/src/main/java/com/careeros/domain/acquisition/AcquiredDocument.java
- Modify: career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/AcquisitionJpaModels.java
- Modify: career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JpaAcquisitionStore.java
- Create: career-infrastructure/src/main/resources/db/migration/V43__audited_transport.sql
- Test: JavaHttpDocumentFetcherTest.java, JpaAcquisitionStoreTest.java, MigrationIntegrationTest.java

**Interfaces:**
- Produces redirect-safe audited HTTP and persisted transport_risk, default NONE.

- [ ] **Step 1: Write failing security tests**

Test ordinary HTTP rejection, exact host/path acceptance, out-of-prefix/cross-host redirect rejection, nondefault-port rejection, GET/HEAD restriction, HEAD empty body, no credential headers/body, and plaintext risk marking.

- [ ] **Step 2: Verify RED**

    mvn -pl career-infrastructure -am '-Dtest=JavaHttpDocumentFetcherTest,JpaAcquisitionStoreTest,MigrationIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

- [ ] **Step 3: Enforce the read contract**

Add validateRequestTarget(FetchRequest, URI) and pathAllowed(URI, Set<String>). Revalidate every redirect. Keep fixed headers, timeouts, limits, pacing, conditional requests, and retries.

- [ ] **Step 4: Persist transport risk**

V43 adds transport_risk varchar(32) not null default 'NONE' to the acquired-document/fetch storage used by the model. Map domain/JPA values without introducing a second fingerprint.

- [ ] **Step 5: Verify GREEN and commit**

    mvn -pl career-infrastructure -am '-Dtest=JavaHttpDocumentFetcherTest,JpaAcquisitionStoreTest,MigrationIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    git add career-domain career-infrastructure
    git commit -m "feat: add audited read-only HTTP transport"

---

### Task 4: Unified listing delegation and status semantics

**Files:**
- Modify: career-application/src/main/java/com/careeros/application/AcquisitionService.java
- Modify: career-application/src/main/java/com/careeros/application/SourceConnectionProjector.java
- Test: AcquisitionServiceTest.java, SourceConnectionProjectorTest.java

**Interfaces:**
- Both incremental and historical workflows consume SourceListingReader.
- A fresh successful crawl with an unprovable required historical entry remains PARTIAL.

- [ ] **Step 1: Write failing application tests**

Prove non-JCMS incremental delegation, required-entry incompleteness blocking COMPLETE, disabled → NOT_CONNECTED, three failures → FAILED, and conclusive years/checkpoints → CONNECTED.

- [ ] **Step 2: Verify RED**

    mvn -pl career-application '-Dtest=AcquisitionServiceTest,SourceConnectionProjectorTest' test

- [ ] **Step 3: Remove JCMS-specific application branching**

Both workflows call listingReader.read with historical false/true. Keep stable identities, fingerprints, change generation, disappearance protection, locks, and attachments.

- [ ] **Step 4: Verify GREEN and commit**

    mvn -pl career-application test
    git add career-application
    git commit -m "refactor: unify source listing workflows"

---

### Task 5: Official employment identity, employer, and worksite

**Files:**
- Modify: career-domain/src/main/java/com/careeros/domain/DomainEnums.java
- Modify: career-domain/src/main/java/com/careeros/domain/JobPosting.java
- Modify: OfficialJobFieldMapper.java, OfficialExcelImportService.java, HospitalOfficialPageParser.java, HospitalOfficialJobImportService.java, DefaultJobUpsertService.java, JpaModels.java
- Create: career-infrastructure/src/main/resources/db/migration/V44__official_job_identity.sql
- Test: corresponding mapper/importer/parser/upsert tests

**Interfaces:**
- Produces QUOTA_OR_FILING, UNIT_FORMAL, SOE_FORMAL plus actualEmployer/worksite with evidence.
- Ambiguous 合同制/聘用制 remains CONTRACT or UNKNOWN.

- [ ] **Step 1: Write failing mapping and import tests**

Cases: 事业编制, 员额/备案制, 单位正式聘用, 国企正式劳动合同, 编外/项目聘用, 劳务派遣, two hospital campuses, SOE subsidiary vs publishing group, and missing wording → UNKNOWN.

- [ ] **Step 2: Verify RED**

    mvn -pl career-infrastructure -am '-Dtest=OfficialJobFieldMapperTest,OfficialExcelImportServiceTest,HospitalOfficialPageParserTest,HospitalOfficialJobImportServiceTest,DefaultJobUpsertServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

- [ ] **Step 3: Extend domain/schema and row-first normalization**

Add compatible domain fields and nullable actual_employer/worksite/evidence storage in the forward-only V44 migration. Row fields win; announcement defaults are fallback. Include normalized values in content fingerprints and evidence fragments. Do not modify the already committed V43 transport migration.

- [ ] **Step 4: Verify GREEN and commit**

Run Step 2 plus the infrastructure module, then:

    git add career-domain career-infrastructure
    git commit -m "feat: preserve official employment identity"

---

### Task 6: Batch 1 contracts and First Hospital recovery

**Files:**
- Create: career-infrastructure/src/main/resources/db/migration/V45__onboard_hangzhou_source_completion_batch1.sql
- Modify: career-infrastructure/src/main/resources/official-source-catalog.yml
- Modify: career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/OfficialSourceCatalog.java
- Modify: career-application/src/main/java/com/careeros/application/AcquisitionHttpPorts.java
- Modify: career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/ListingEntryContract.java
- Modify: career-infrastructure/src/main/java/com/careeros/infrastructure/acquisition/JavaHttpDocumentFetcher.java
- Test: OfficialSourceCatalogTest.java, MigrationIntegrationTest.java, AcquisitionHttpPortsTest.java, JavaHttpDocumentFetcherTest.java

**Interfaces:**
- Adds IDs ...0404–...0411 for Fuyang, Linan, Shangcheng, Jiande, Tonglu, TCM Hospital, Xixi Hospital, Data Group.
- Repairs First Hospital in place at ...0305.

- [ ] **Step 1: Write failing exact-contract tests**

Assert eight sources, exact entries/hosts/years/UUIDs/checkpoints/coverage. First Hospital must exclude `/dynamic/articles_tag2/id/68`, `zp.hz-hospital.com`, and `renshi.wechathospital.com` from daily evidence; use linked-page announcement/lifecycle entries rooted at exact authority `124.160.72.42:8080`, plus a non-conclusive campaign-state entry. Prove page 13 terminates by missing next link and page 14 repetition cannot establish completion.

- [ ] **Step 2: Verify RED**

    mvn -pl career-infrastructure -am '-Dtest=OfficialSourceCatalogTest,MigrationIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

- [ ] **Step 3: Add V45 and YAML mirror**

First extend audited HTTP contracts with an explicit exact-port/authority allowlist while preserving default-port-only behavior for existing contracts. Keep GET/HEAD only, exact path prefixes, no credentials/cookies, and revalidate every redirect. Then use idempotent source upserts, staggered crons, independent host/path rules, initial PARTIAL, and REGISTERED/VERIFIED. Retain First Hospital 2024 PARTIAL, allow 2025—2026 conclusions only after the 13-page archive reconciles, and clear obsolete consecutive failure state.

- [ ] **Step 4: Verify GREEN and commit**

    mvn -pl career-infrastructure -am '-Dtest=OfficialSourceCatalogTest,MigrationIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    git add career-infrastructure
    git commit -m "feat: onboard Hangzhou source batch one"

---

### Task 7: Shared live acceptance and Batch 1 evidence

**Files:**
- Create: scripts/hangzhou_source_batch_acceptance.ps1
- Create: docs/hangzhou-source-status-matrix.md

**Interfaces:**
- Script executes incremental → historical 2024–2026 → incremental, polls terminal states, rejects FAILED, and records counts/run IDs.

- [ ] **Step 1: Implement script with dry-run/input tests**

The second unchanged run must have zero added/updated records. WAF/archive gaps are PARTIAL with evidence, not synthetic success.

- [ ] **Step 2: Rebuild and verify health**

    ./scripts/start-career-os.ps1 -Rebuild -NoBrowser
    Invoke-RestMethod http://localhost:8080/actuator/health

Expected status: UP.

- [ ] **Step 3: Accept all nine Batch 1 targets**

    ./scripts/hangzhou_source_batch_acceptance.ps1 -Codes HZ_FUYANG_GOV,HZ_LINAN_GOV,HZ_SHANGCHENG_GOV,HZ_JIANDE_GOV,HZ_TONGLU_GOV,HZ_TCM_HOSPITAL,HZ_XIXI_HOSPITAL,HZ_DATA_GROUP,HZ_FIRST_HOSPITAL -FromYear 2024 -ToYear 2026

- [ ] **Step 4: Convert each live defect into RED/GREEN regression tests**

No production fix is made without first reproducing it deterministically.

- [ ] **Step 5: Commit and push evidence**

    git add scripts/hangzhou_source_batch_acceptance.ps1 docs/hangzhou-source-status-matrix.md
    git commit -m "test: accept Hangzhou source batch one"
    git push origin codex/phase4b-career-workbench

---

### Task 8: Batch 2 multi-channel contracts and acceptance

**Files:**
- Create: career-infrastructure/src/main/resources/db/migration/V46__onboard_hangzhou_source_completion_batch2.sql
- Modify: official-source-catalog.yml
- Test: catalog, migration, reader, discoverer, and fetcher tests
- Update: docs/hangzhou-source-status-matrix.md

**Interfaces:**
- Adds IDs ...0412–...0419 for Binjiang, Linping, Xiaoshan, Yuhang, Chunan, HZNU, Capital Group, Children's Hospital.

- [ ] **Step 1: Write failing exact-contract tests**

Assert old/new district lanes, UUID regexes, HZNU HRSS official cross-host links, Capital API/archive roles, Children's recruitment host, and no third-party canonical authority.

- [ ] **Step 2: Verify RED, implement V46/YAML, verify GREEN, commit**

    git commit -m "feat: onboard Hangzhou source batch two"

- [ ] **Step 3: Run live incremental/history/incremental acceptance**

Use the Task 7 script. A bounded Playwright discovery adapter is permitted only for proven WAF blocking; downloaded documents still use the standard fingerprint pipeline.

- [ ] **Step 4: Update evidence, run regressions, commit, and push**

    git commit -m "test: accept Hangzhou source batch two"
    git push origin codex/phase4b-career-workbench

---

### Task 9: Batch 3 custom and audited-HTTP sources

**Files:**
- Create: V47__onboard_hangzhou_source_completion_batch3.sql
- Modify: official-source-catalog.yml
- Create: EmbeddedPositionDiscoverer.java and ScriptPropertySourceDiscoverer.java
- Modify: RoutingSourceDiscoverer.java
- Test: custom discoverer, catalog, migration, reader, and fetcher tests
- Update: docs/hangzhou-source-status-matrix.md

**Interfaces:**
- Adds IDs ...0420–...0423 for Westlake, Metro, ZJUT, UCAS.

- [ ] **Step 1: Write failing adapter tests**

Westlake extracts only research_team; Metro stores campaign state without historical absence; ZJUT extracts JS url properties; UCAS separates formal research from temporary support under one source summary.

- [ ] **Step 2: Verify RED and implement minimal adapters**

Missing/malformed configured embedded data is a contract failure.

- [ ] **Step 3: Add V47 and catalog mirror**

ZJUT/UCAS use exact audited HTTP hosts/path prefixes. Metro uses CAMPAIGN_STATE. Westlake uses the official accessible English host.

- [ ] **Step 4: Verify focused tests and commit**

    mvn -pl career-infrastructure -am '-Dtest=*Listing*Test,*DiscovererTest,JavaHttpDocumentFetcherTest,OfficialSourceCatalogTest,MigrationIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    git commit -m "feat: onboard Hangzhou source batch three"

- [ ] **Step 5: Run live acceptance and push evidence**

Require NOT_CONNECTED=0 and FAILED=0; documented PARTIAL is allowed only for official archive limits.

---

### Task 10: Lifecycle, API, and UI evidence surface

**Files:**
- Modify: RecruitmentLifecycle.java, Phase2DocumentProcessor.java, OfficialLifecycleDocumentService.java
- Modify: career-web acquisition and job-detail API models
- Modify: career-ui contracts, updates SourceList, and OfficialJobDetail
- Test: domain, processor, persistence, API, UpdatesPage, and OfficialJobDetail tests

**Interfaces:**
- Produces explicit source-gap copy, actual employer/worksite/employment evidence, and lifecycle stage/link evidence.

- [ ] **Step 1: Write failing domain/API/UI tests**

Cover registration, admission ticket, written/professional tests, correction/cancellation, review-required stages, actual employer/worksite, unknown evidence, exactly 28 sources, and separate access/connection/history states.

- [ ] **Step 2: Verify RED**

Run focused Maven tests and:

    Set-Location career-ui
    npm.cmd test -- --run

- [ ] **Step 3: Implement domain → persistence → API → UI**

Preserve unmatched/ambiguous lifecycle rows and never display inferred formal employment.

- [ ] **Step 4: Verify GREEN and commit**

    npm.cmd run verify
    git add career-domain career-infrastructure career-web career-ui
    git commit -m "feat: expose complete source and job evidence"

---

### Task 11: Full verification and private delivery

**Files:**
- Update: README.md, docs/PHASE3_API.md, docs/current-gap-analysis.md
- Finalize: docs/hangzhou-source-status-matrix.md

- [ ] **Step 1: Verify completion matrix**

Exactly 28 unique sources; NOT_CONNECTED=0; FAILED=0; CONNECTED + PARTIAL=28. Every PARTIAL has official gap reason, annual stop reason, and run IDs.

- [ ] **Step 2: Run full backend suite**

    $env:JAVA_HOME=(Resolve-Path '.tooling/temurin-21/jdk-21.0.12+8').Path
    mvn test

Expected: exit 0 and zero failures/errors.

- [ ] **Step 3: Run full frontend verification**

    Set-Location career-ui
    npm.cmd run verify
    Set-Location ..

- [ ] **Step 4: Run correctness/lifecycle acceptance and health**

    ./scripts/career_correctness_acceptance.ps1
    ./scripts/hangzhou_lifecycle_acceptance.ps1
    Invoke-RestMethod http://localhost:8080/actuator/health

- [ ] **Step 5: Perform Playwright acceptance**

Verify npx.cmd exists; inspect /updates and /opportunities; check 28 sources/no failed or unconnected sources; inspect representative district, hospital, university, research, and SOE details; close the browser session.

- [ ] **Step 6: Request independent code review**

Fix every Critical/Important finding with a failing regression test first.

- [ ] **Step 7: Update docs, commit, and push**

    git diff --check
    git status --short
    git add README.md docs
    git commit -m "docs: close Hangzhou source completion"
    git push origin codex/phase4b-career-workbench
    gh repo view career-os --json visibility,url

Expected: PRIVATE visibility, clean worktree, and all verified batch commits on the remote branch.
