package com.careeros.application;

import static com.careeros.application.ExtractionPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.domain.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class Fixtures {
    static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final UUID EVIDENCE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID FRAGMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    static final UUID ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    static final UUID EVENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");

    private Fixtures() {}

    static SubmitExtractionCommand htmlCommand(String html) {
        return new SubmitExtractionCommand(
            html.getBytes(StandardCharsets.UTF_8), "text/html",
            "https://example.gov.cn/notice/1", "2026年公开招聘公告", NOW,
            ORGANIZATION_ID, EVENT_ID, false);
    }

    static <T> ExtractedFact<T> explicit(T value) {
        return new ExtractedFact<>(value, FactStatus.EXPLICIT, 0.98, List.of(FRAGMENT_ID), null);
    }

    static <T> ExtractedFact<T> unknown() {
        return new ExtractedFact<>(null, FactStatus.UNKNOWN, 0, List.of(), null);
    }

    static RecruitmentExtractionProposal verifiedProposal() {
        return proposal(0.96, explicit(OrganizationType.PUBLIC_INSTITUTION));
    }

    static RecruitmentExtractionProposal reviewProposal() {
        return proposal(0.60, new ExtractedFact<>(
            OrganizationType.UNKNOWN, FactStatus.EXPLICIT, 0.95, List.of(FRAGMENT_ID), null));
    }

    private static RecruitmentExtractionProposal proposal(
        double confidence,
        ExtractedFact<OrganizationType> organizationType
    ) {
        var source = new RecruitmentExtractionProposal.SourceProposal(
            EVIDENCE_ID, "https://example.gov.cn/notice/1", "2026年公开招聘公告");
        var organization = new RecruitmentExtractionProposal.OrganizationProposal("杭州市示例单位", organizationType);
        var event = new RecruitmentExtractionProposal.EventProposal(
            "2026年公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            explicit(LocalDate.of(2026, 8, 14)), unknown(), unknown());
        var job = new RecruitmentExtractionProposal.JobProposal(
            explicit("信息中心技术岗"), "A01", explicit(1), explicit(EmploymentType.ESTABLISHMENT),
            "杭州", explicit(EducationLevel.BACHELOR), unknown(), explicit("计算机类"),
            unknown(), unknown(), unknown(), JobFamily.INFORMATION_SYSTEMS, "系统建设与运维");
        return new RecruitmentExtractionProposal(
            RecruitmentExtractionProposal.SCHEMA_VERSION, source, organization, event,
            List.of(job), List.of(), confidence, true);
    }

    static ParsedDocument parsed(Evidence evidence) {
        var fragment = new EvidenceFragment(
            FRAGMENT_ID, evidence.id(), LocatorType.HTML, Map.of("cssSelector", "body"),
            "信息中心技术岗，事业编制，本科及以上，计算机类", "fragment-hash", NOW);
        return new ParsedDocument("fixture-html", "1.0.0", ParserQuality.ACCEPTABLE, List.of(fragment), List.of());
    }

    static final class MemoryArtifactStore implements ArtifactStore {
        private final Map<UUID, byte[]> content = new ConcurrentHashMap<>();

        @Override
        public SourceArtifact put(byte[] bytes, String mediaType, Instant capturedAt) {
            UUID id = UUID.randomUUID();
            content.put(id, bytes.clone());
            return new SourceArtifact(id, sha256(bytes), mediaType, bytes.length, "memory://" + id, capturedAt);
        }

        @Override
        public InputStream open(SourceArtifact artifact) {
            return new ByteArrayInputStream(content.get(artifact.id()));
        }
    }

    static final class HtmlParser implements DocumentParser {
        @Override public ParserDescriptor descriptor() { return new ParserDescriptor("fixture-html", "1.0.0"); }
        @Override public boolean supports(String mediaType) { return "text/html".equals(mediaType); }
        @Override public ParsedDocument parse(SourceArtifact artifact, InputStream input, Evidence evidence) {
            return parsed(evidence);
        }
    }

    static final class CountingExtractor implements StructuredExtractor {
        private final RecruitmentExtractionProposal proposal;
        private final boolean enabled;
        private final java.util.List<String> warnings;
        private int calls;

        CountingExtractor(RecruitmentExtractionProposal proposal, boolean enabled) {
            this(proposal, enabled, java.util.List.of());
        }

        CountingExtractor(
            RecruitmentExtractionProposal proposal, boolean enabled, java.util.List<String> warnings) {
            this.proposal = proposal;
            this.enabled = enabled;
            this.warnings = warnings;
        }

        @Override public ExtractorDescriptor descriptor() {
            return new ExtractorDescriptor(enabled ? "openai" : "no-model", "1.0.0", enabled ? "fixture-model" : "none", "p1", enabled);
        }

        @Override public ExtractionAttempt extract(ParsedDocument document, ExtractionContext context) {
            calls++;
            return new ExtractionAttempt(proposal, enabled ? "{fixture:true}" : null, warnings);
        }

        int calls() { return calls; }
    }

    static final class MemoryExtractionPersistence implements ExtractionPersistence {
        private final Map<String, PersistedExtraction> byFingerprint = new ConcurrentHashMap<>();
        private final Map<UUID, ReviewItem> reviews = new ConcurrentHashMap<>();
        private RuntimeException findFailure;

        @Override public Optional<PersistedExtraction> findByInputFingerprint(String fingerprint) {
            return Optional.ofNullable(byFingerprint.get(fingerprint));
        }

        @Override public PersistedExtraction save(ExtractionBundle bundle) {
            PersistedExtraction value = new PersistedExtraction(
                bundle.run(), Optional.ofNullable(bundle.review()).map(ReviewItem::id));
            byFingerprint.put(bundle.run().inputFingerprint(), value);
            if (bundle.review() != null) reviews.put(bundle.review().id(), bundle.review());
            return value;
        }

        @Override public PersistedExtraction saveFailure(FailedExtractionBundle bundle) {
            PersistedExtraction value = new PersistedExtraction(bundle.run(), Optional.empty());
            byFingerprint.put(bundle.run().inputFingerprint(), value);
            return value;
        }

        @Override public PersistedExtraction findById(UUID id) {
            if (findFailure != null) throw findFailure;
            return byFingerprint.values().stream().filter(value -> value.run().id().equals(id)).findFirst().orElseThrow();
        }

        PersistedExtraction onlyValue() { return byFingerprint.values().iterator().next(); }
        ReviewItem onlyReview() { return reviews.values().iterator().next(); }
        void failFindWith(RuntimeException failure) { this.findFailure = failure; }
    }

    static final class SynchronizedFingerprintLock implements FingerprintLock {
        private final Map<String, Object> locks = new ConcurrentHashMap<>();

        @Override public <T> T execute(String fingerprint, java.util.function.Supplier<T> operation) {
            Object lock = locks.computeIfAbsent(fingerprint, ignored -> new Object());
            try {
                synchronized (lock) {
                    return operation.get();
                }
            } finally {
                locks.remove(fingerprint, lock);
            }
        }
    }

    static final class MemoryReviewPersistence implements ReviewPersistence {
        private ReviewDetails details;

        MemoryReviewPersistence(ReviewDetails details) { this.details = details; }
        @Override public ReviewDetails findById(UUID id) {
            if (!details.item().id().equals(id)) throw new ExtractionExceptions.ReviewNotFoundException("Review not found: " + id);
            return details;
        }
        @Override public ReviewPage findPage(ReviewStatus status, int page, int size) {
            List<ReviewItem> items = details.item().status() == status ? List.of(details.item()) : List.of();
            return new ReviewPage(items, page, size, items.size());
        }
        @Override public ReviewDetails apply(ReviewResolution resolution) {
            ReviewItem changedItem = details.item().apply(resolution.action());
            DataQualityStatus target = switch (resolution.action().decision()) {
                case CONFIRM, CORRECT -> DataQualityStatus.VERIFIED;
                case REJECT -> DataQualityStatus.REJECTED;
                case NEED_MORE_EVIDENCE -> DataQualityStatus.REVIEW_REQUIRED;
            };
            ExtractionRun changedRun = target == DataQualityStatus.REVIEW_REQUIRED
                ? details.run()
                : details.run().transitionTo(target, resolution.action().actedAt());
            details = new ReviewDetails(changedItem, changedRun, details.fragments());
            return details;
        }
    }

    static final class RecordingWriter implements VerifiedProposalWriter {
        int calls;
        @Override public JobUpsertService.JobUpsertResult write(
            RecruitmentExtractionProposal proposal, List<UUID> evidenceIds) {
            calls++;
            return new JobUpsertService.JobUpsertResult(1, 0, 0, 0, List.of(UUID.randomUUID()));
        }
    }

    static final class RecordingUnitOfWork implements UnitOfWork {
        int calls;
        @Override public <T> T execute(java.util.function.Supplier<T> operation) {
            calls++;
            return operation.get();
        }
    }

    static ReviewDetails pendingReviewDetails(long version) {
        RecruitmentExtractionProposal proposal = verifiedProposal();
        UUID runId = UUID.fromString("10000000-0000-0000-0000-000000000010");
        UUID reviewId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        ExtractionRun run = new ExtractionRun(
            runId, EVIDENCE_ID, ORGANIZATION_ID, EVENT_ID, "fingerprint", ExtractionSourceType.HTML,
            "fixture-html", "1.0.0", "openai", "1.0.0", "fixture-model", "p1",
            RecruitmentExtractionProposal.SCHEMA_VERSION, DataQualityStatus.REVIEW_REQUIRED, 0.96,
            proposal, "{}", null, null, NOW.minusSeconds(1), NOW);
        ReviewItem item = new ReviewItem(
            reviewId, runId, ReviewStatus.PENDING, version, proposal, List.of(), List.of(), NOW, null);
        return new ReviewDetails(item, run, parsed(new Evidence(
            EVIDENCE_ID, UUID.randomUUID(), EvidenceType.OFFICIAL_NOTICE,
            proposal.source().sourceUrl(), proposal.source().sourceTitle(), null, "hash", NOW)).fragments());
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
