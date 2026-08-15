package com.careeros.infrastructure.extraction;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.ExtractionExceptions;
import com.careeros.application.ExtractionPorts.ExtractionBundle;
import com.careeros.application.ExtractionPorts.ExtractionPersistence;
import com.careeros.application.ExtractionPorts.ReviewPersistence;
import com.careeros.application.ExtractionPorts.ReviewResolution;
import com.careeros.domain.Evidence;
import com.careeros.domain.EvidenceFragment;
import com.careeros.domain.ExtractionRun;
import com.careeros.domain.ParsedDocument;
import com.careeros.domain.ReviewAction;
import com.careeros.domain.ReviewIssue;
import com.careeros.domain.ReviewItem;
import com.careeros.domain.ReviewPayload;
import com.careeros.domain.SourceArtifact;
import com.careeros.infrastructure.persistence.PersistenceAdaptersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = ExtractionPersistenceIntegrationTest.TestApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class ExtractionPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os")
        .withUsername("career_os")
        .withPassword("career_os");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    void savesAnAtomicBundleAndResolvesReviewWithAppendOnlyHistory(
        @Autowired ExtractionPersistence extractions,
        @Autowired ReviewPersistence reviews
    ) {
        ExtractionBundle bundle = bundle("a", "f".repeat(64));

        var saved = extractions.save(bundle);

        assertThat(saved.reviewId()).contains(bundle.review().id());
        assertThat(extractions.findByInputFingerprint(bundle.run().inputFingerprint())).isPresent();
        assertThat(reviews.findPage(ReviewStatus.PENDING, 0, 20).items())
            .extracting(ReviewItem::id)
            .contains(bundle.review().id());
        assertThat(reviews.findById(bundle.review().id()).fragments()).hasSize(1);

        ReviewAction moreEvidence = action(bundle.review(), ReviewDecision.NEED_MORE_EVIDENCE, 0);
        var stillPending = reviews.apply(new ReviewResolution(
            bundle.review(), moreEvidence, bundle.run().proposedPayload()));
        assertThat(stillPending.item().status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(stillPending.item().version()).isEqualTo(1);

        ReviewAction confirm = action(stillPending.item(), ReviewDecision.CONFIRM, 1);
        var resolved = reviews.apply(new ReviewResolution(
            stillPending.item(), confirm, bundle.run().proposedPayload()));
        assertThat(resolved.item().status()).isEqualTo(ReviewStatus.RESOLVED);
        assertThat(resolved.run().status()).isEqualTo(DataQualityStatus.VERIFIED);
        assertThat(resolved.item().actions())
            .extracting(ReviewAction::decision)
            .containsExactly(ReviewDecision.NEED_MORE_EVIDENCE, ReviewDecision.CONFIRM);

        assertThatThrownBy(() -> reviews.apply(new ReviewResolution(
            bundle.review(), action(bundle.review(), ReviewDecision.REJECT, 0),
            bundle.run().proposedPayload())))
            .isInstanceOf(ExtractionExceptions.ReviewConflictException.class);
    }

    @Test
    void databaseRejectsDuplicateInputFingerprintsAndRollsBackTheWholeBundle(
        @Autowired ExtractionPersistence extractions,
        @Autowired JdbcTemplate jdbc
    ) {
        String duplicate = "d".repeat(64);
        extractions.save(bundle("b", duplicate));

        assertThatThrownBy(() -> extractions.save(bundle("c", duplicate)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
            "select count(*) from source_artifact where sha256 = ?", Long.class, "c".repeat(64)))
            .isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from evidence where content_hash = ?", Long.class, "c".repeat(64)))
            .isZero();
    }

    @Test
    void legacyV3ReviewSummaryRemainsReadableAfterPayloadUpgrade(
        @Autowired ExtractionPersistence extractions,
        @Autowired ReviewPersistence reviews,
        @Autowired JdbcTemplate jdbc
    ) {
        ExtractionBundle bundle = bundle("e", "1".repeat(64));
        extractions.save(bundle);
        UUID actionId = UUID.randomUUID();
        jdbc.update("""
            insert into review_action
                (id, review_item_id, decision, expected_version, original_payload, note, acted_at)
            values (?, ?, 'NEED_MORE_EVIDENCE', 0, cast(? as jsonb), 'legacy', ?)
            """,
            actionId,
            bundle.review().id(),
            "{\"schemaVersion\":\"1.0.0\",\"sourceUrl\":\"https://legacy.example\",\"confidence\":0.8,\"jobCount\":1}",
            Timestamp.from(NOW.plusSeconds(5)));

        ReviewAction legacy = reviews.findById(bundle.review().id()).item().actions().getFirst();

        assertThat(legacy.originalPayload().format())
            .isEqualTo(ReviewPayload.Format.LEGACY_SUMMARY_V0);
        assertThat(legacy.originalPayload().legacySummary()).containsEntry("jobCount", 1);
    }

    private static ExtractionBundle bundle(String hashSeed, String fingerprint) {
        UUID artifactId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID fragmentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        String hash = hashSeed.repeat(64);
        SourceArtifact artifact = new SourceArtifact(
            artifactId, hash, "text/html", 128, "file:///artifacts/" + hash, NOW);
        Evidence evidence = new Evidence(
            evidenceId, artifactId, EvidenceType.OFFICIAL_NOTICE,
            "https://example.gov.cn/notices/" + evidenceId, "公开招聘公告", null, hash, NOW);
        EvidenceFragment fragment = new EvidenceFragment(
            fragmentId, evidenceId, LocatorType.HTML, Map.of("cssSelector", "#job-a01"),
            "信息中心技术岗，事业编制，本科及以上，计算机类", hash, NOW);
        ParsedDocument parsed = new ParsedDocument(
            "jsoup", "1.22.2", ParserQuality.ACCEPTABLE, List.of(fragment), List.of());
        ExtractionRun run = new ExtractionRun(
            runId, evidenceId, null, null, fingerprint, ExtractionSourceType.HTML,
            "jsoup", "1.22.2", "openai-structured", "1.0.0", "gpt-test", "1.0.0",
            "1.0.0", DataQualityStatus.REVIEW_REQUIRED, 0.80,
            ProposalFixtures.validProposal(), "{}", null, null, NOW, NOW.plusSeconds(2));
        ReviewIssue issue = new ReviewIssue(
            UUID.randomUUID(), reviewId, ReviewReasonCode.LOW_CONFIDENCE,
            "confidence", "需要人工复核", fragmentId);
        ReviewItem review = new ReviewItem(
            reviewId, runId, ReviewStatus.PENDING, 0, run.proposedPayload(),
            List.of(issue), List.of(), NOW.plusSeconds(2), null);
        return new ExtractionBundle(artifact, evidence, parsed, run, review);
    }

    private static ReviewAction action(ReviewItem item, ReviewDecision decision, long version) {
        return new ReviewAction(
            UUID.randomUUID(), item.id(), decision, version,
            ReviewPayload.full(item.proposal()), null, "集成测试", NOW.plusSeconds(10 + version));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = {
        "com.careeros.infrastructure.persistence",
        "com.careeros.infrastructure.extraction"
    })
    @EnableJpaRepositories(basePackages = {
        "com.careeros.infrastructure.persistence",
        "com.careeros.infrastructure.extraction"
    })
    @Import({PersistenceAdaptersConfiguration.class, JpaExtractionPersistence.class, SpringTransactionUnitOfWork.class})
    static class TestApplication {}
}
