package com.careeros.infrastructure.extraction;

import static com.careeros.domain.DomainEnums.*;

import com.careeros.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ProposalFixtures {
    static final UUID EVIDENCE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID FRAGMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

    private ProposalFixtures() {}

    static RecruitmentExtractionProposal validProposal() {
        return proposalWithEvidence(FRAGMENT_ID);
    }

    static RecruitmentExtractionProposal proposalWithEvidence(UUID fragmentId) {
        var source = new RecruitmentExtractionProposal.SourceProposal(
            EVIDENCE_ID, "https://example.gov.cn/notice/1", "2026年公开招聘公告");
        var organization = new RecruitmentExtractionProposal.OrganizationProposal(
            "杭州市示例单位", explicit(OrganizationType.PUBLIC_INSTITUTION, fragmentId));
        var event = new RecruitmentExtractionProposal.EventProposal(
            "2026年公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            explicit(LocalDate.of(2026, 8, 14), fragmentId), unknown(), unknown());
        var job = new RecruitmentExtractionProposal.JobProposal(
            explicit("信息中心技术岗", fragmentId), "A01", explicit(1, fragmentId),
            explicit(EmploymentType.ESTABLISHMENT, fragmentId), "杭州",
            explicit(EducationLevel.BACHELOR, fragmentId), unknown(), explicit("计算机类", fragmentId),
            unknown(), unknown(), unknown(), JobFamily.INFORMATION_SYSTEMS, "系统建设与运维");
        return new RecruitmentExtractionProposal(
            RecruitmentExtractionProposal.SCHEMA_VERSION, source, organization, event,
            List.of(job), List.of(), 0.96, true);
    }

    static RecruitmentExtractionProposal interpretedEmploymentProposal() {
        RecruitmentExtractionProposal valid = validProposal();
        var original = valid.jobs().getFirst();
        var interpreted = new ExtractedFact<>(
            EmploymentType.ESTABLISHMENT, FactStatus.INTERPRETED, 0.95,
            List.of(FRAGMENT_ID), "根据上下文推断");
        var changed = new RecruitmentExtractionProposal.JobProposal(
            original.title(), original.externalJobCode(), original.headcount(), interpreted,
            original.location(), original.minimumEducation(), original.degree(), original.majorText(),
            original.maximumAge(), original.acceptedGraduationYears(), original.minimumExperienceYears(),
            original.jobFamily(), original.duties());
        return new RecruitmentExtractionProposal(
            valid.schemaVersion(), valid.source(), valid.organization(), valid.recruitmentEvent(),
            List.of(changed), valid.warnings(), valid.confidence(), valid.completeSnapshot());
    }

    static EvidenceFragment fragment() {
        return new EvidenceFragment(
            FRAGMENT_ID, EVIDENCE_ID, LocatorType.HTML, Map.of("cssSelector", "#job-a01"),
            "2026年8月14日发布：信息中心技术岗，事业编制，本科及以上，计算机类",
            "fragment-hash", NOW);
    }

    static ParsedDocument parsedWithoutTable() {
        return new ParsedDocument(
            "jsoup", "1.22.2", ParserQuality.ACCEPTABLE,
            List.of(new EvidenceFragment(
                FRAGMENT_ID, EVIDENCE_ID, LocatorType.HTML, Map.of("cssSelector", "h1"),
                "2026年公开招聘公告", "heading-hash", NOW)),
            List.of());
    }

    static Evidence evidence() {
        return new Evidence(
            EVIDENCE_ID, UUID.randomUUID(), EvidenceType.OFFICIAL_NOTICE,
            "https://example.gov.cn/notice/1", "2026年公开招聘公告",
            null, "artifact-hash", NOW);
    }

    private static <T> ExtractedFact<T> explicit(T value, UUID fragmentId) {
        return new ExtractedFact<>(value, FactStatus.EXPLICIT, 0.98, List.of(fragmentId), null);
    }

    private static <T> ExtractedFact<T> unknown() {
        return new ExtractedFact<>(null, FactStatus.UNKNOWN, 0, List.of(), null);
    }
}
