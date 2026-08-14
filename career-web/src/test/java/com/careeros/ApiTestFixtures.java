package com.careeros;

import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.ExtractionPorts.PersistedExtraction;
import com.careeros.application.ExtractionPorts.ReviewDetails;
import com.careeros.domain.ExtractedFact;
import com.careeros.domain.ExtractionRun;
import com.careeros.domain.RecruitmentExtractionProposal;
import com.careeros.domain.ReviewItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class ApiTestFixtures {
    static final UUID RUN_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID EVIDENCE_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    static final UUID REVIEW_ID = UUID.fromString("40000000-0000-0000-0000-000000000003");
    static final Instant NOW = Instant.parse("2026-08-14T14:00:00Z");

    private ApiTestFixtures() {}

    static RecruitmentExtractionProposal proposal() {
        return new RecruitmentExtractionProposal(
            RecruitmentExtractionProposal.SCHEMA_VERSION,
            new RecruitmentExtractionProposal.SourceProposal(
                EVIDENCE_ID, "https://example.gov.cn/notice/1", "公开招聘公告"),
            new RecruitmentExtractionProposal.OrganizationProposal(
                "杭州市测试单位", unknown()),
            new RecruitmentExtractionProposal.EventProposal(
                "2026年公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
                unknown(), unknown(), unknown()),
            List.of(), List.of("MODEL_UNAVAILABLE"), 0.50, false);
    }

    static ExtractionRun run() {
        return new ExtractionRun(
            RUN_ID, EVIDENCE_ID, null, null, "f".repeat(64), ExtractionSourceType.HTML,
            "jsoup", "1.22.2", "no-model", "1.0.0", "none", "1.0.0", "1.0.0",
            DataQualityStatus.REVIEW_REQUIRED, 0.50, proposal(), null, null, null, NOW, NOW);
    }

    static ReviewItem review() {
        return new ReviewItem(
            REVIEW_ID, RUN_ID, ReviewStatus.PENDING, 0, proposal(),
            List.of(), List.of(), NOW, null);
    }

    static ReviewDetails details() { return new ReviewDetails(review(), run(), List.of()); }
    static PersistedExtraction persisted() { return new PersistedExtraction(run(), Optional.of(REVIEW_ID)); }

    private static <T> ExtractedFact<T> unknown() {
        return new ExtractedFact<>(null, FactStatus.UNKNOWN, 0, List.of(), null);
    }
}
