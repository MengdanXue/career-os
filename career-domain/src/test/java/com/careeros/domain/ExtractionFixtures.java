package com.careeros.domain;

import static com.careeros.domain.DomainEnums.DataQualityStatus;
import static com.careeros.domain.DomainEnums.EducationLevel;
import static com.careeros.domain.DomainEnums.EmploymentType;
import static com.careeros.domain.DomainEnums.EventType;
import static com.careeros.domain.DomainEnums.ExtractionSourceType;
import static com.careeros.domain.DomainEnums.FactStatus;
import static com.careeros.domain.DomainEnums.JobFamily;
import static com.careeros.domain.DomainEnums.LocatorType;
import static com.careeros.domain.DomainEnums.OrganizationType;
import static com.careeros.domain.DomainEnums.ParserQuality;
import static com.careeros.domain.DomainEnums.ReviewStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ExtractionFixtures {
    static final UUID EVIDENCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID FRAGMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");

    private ExtractionFixtures() {}

    static EvidenceFragment fragment() {
        return new EvidenceFragment(
            FRAGMENT_ID,
            EVIDENCE_ID,
            LocatorType.HTML,
            Map.of("cssSelector", "#jobs tr:nth-child(2)"),
            "信息中心技术岗，事业编制，本科及以上，计算机类，年龄35周岁以下",
            "fragment-sha256",
            NOW
        );
    }

    static ParsedDocument parsed(ParserQuality quality) {
        return new ParsedDocument("fixture-parser", "1.0.0", quality, List.of(fragment()), List.of());
    }

    static <T> ExtractedFact<T> explicit(T value) {
        return new ExtractedFact<>(value, FactStatus.EXPLICIT, 0.98, List.of(FRAGMENT_ID), null);
    }

    static <T> ExtractedFact<T> unknown() {
        return new ExtractedFact<>(null, FactStatus.UNKNOWN, 0.0, List.of(), null);
    }

    static RecruitmentExtractionProposal proposal() {
        var source = new RecruitmentExtractionProposal.SourceProposal(
            EVIDENCE_ID, "https://example.gov.cn/notice/1", "2026年公开招聘公告");
        var organization = new RecruitmentExtractionProposal.OrganizationProposal(
            "杭州市示例事业单位", explicit(OrganizationType.PUBLIC_INSTITUTION));
        var event = new RecruitmentExtractionProposal.EventProposal(
            "2026年公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            explicit(LocalDate.of(2026, 8, 14)), unknown(), unknown());
        var job = new RecruitmentExtractionProposal.JobProposal(
            explicit("信息中心技术岗"), "A01", explicit(1), explicit(EmploymentType.ESTABLISHMENT),
            "杭州", explicit(EducationLevel.BACHELOR), unknown(), explicit("计算机类"),
            explicit(35), unknown(), unknown(), JobFamily.INFORMATION_SYSTEMS, "信息系统建设运维");
        return new RecruitmentExtractionProposal(
            RecruitmentExtractionProposal.SCHEMA_VERSION,
            source,
            organization,
            event,
            List.of(job),
            List.of(),
            0.96,
            true
        );
    }

    static ExtractionRun run(DataQualityStatus status) {
        return new ExtractionRun(
            UUID.fromString("00000000-0000-0000-0000-000000000010"),
            EVIDENCE_ID,
            ORGANIZATION_ID,
            EVENT_ID,
            "input-fingerprint",
            ExtractionSourceType.HTML,
            "fixture-parser",
            "1.0.0",
            "deterministic",
            "1.0.0",
            null,
            "none",
            RecruitmentExtractionProposal.SCHEMA_VERSION,
            status,
            0.96,
            proposal(),
            null,
            null,
            null,
            NOW,
            status == DataQualityStatus.RAW ? null : NOW.plusSeconds(1)
        );
    }

    static ReviewItem pendingReview(long version) {
        return new ReviewItem(
            UUID.fromString("00000000-0000-0000-0000-000000000020"),
            run(DataQualityStatus.REVIEW_REQUIRED).id(),
            ReviewStatus.PENDING,
            version,
            proposal(),
            List.of(),
            List.of(),
            NOW,
            null
        );
    }
}
