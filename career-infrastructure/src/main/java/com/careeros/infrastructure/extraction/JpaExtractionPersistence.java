package com.careeros.infrastructure.extraction;

import static com.careeros.application.ExtractionPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.ExtractionExceptions;
import com.careeros.application.RepositoryPorts;
import com.careeros.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaExtractionPersistence {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final SourceArtifactJpaRepository artifacts;
    private final RepositoryPorts.EvidenceRecords evidenceRecords;
    private final EvidenceFragmentJpaRepository fragments;
    private final ExtractionRunJpaRepository runs;
    private final ReviewItemJpaRepository reviews;
    private final ReviewIssueJpaRepository issues;
    private final ReviewActionJpaRepository actions;
    private final ObjectMapper json;
    private final EntityManager entityManager;

    public JpaExtractionPersistence(
        SourceArtifactJpaRepository artifacts,
        RepositoryPorts.EvidenceRecords evidenceRecords,
        EvidenceFragmentJpaRepository fragments,
        ExtractionRunJpaRepository runs,
        ReviewItemJpaRepository reviews,
        ReviewIssueJpaRepository issues,
        ReviewActionJpaRepository actions,
        EntityManager entityManager
    ) {
        this.artifacts = Objects.requireNonNull(artifacts);
        this.evidenceRecords = Objects.requireNonNull(evidenceRecords);
        this.fragments = Objects.requireNonNull(fragments);
        this.runs = Objects.requireNonNull(runs);
        this.reviews = Objects.requireNonNull(reviews);
        this.issues = Objects.requireNonNull(issues);
        this.actions = Objects.requireNonNull(actions);
        this.json = new ObjectMapper().findAndRegisterModules();
        this.entityManager = Objects.requireNonNull(entityManager);
    }

    @Transactional(readOnly = true)
    public Optional<PersistedExtraction> findByInputFingerprint(String fingerprint) {
        return runs.findByInputFingerprint(fingerprint).map(this::toPersisted);
    }

    @Transactional
    public PersistedExtraction save(ExtractionBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        artifacts.saveAndFlush(toArtifactEntity(bundle.artifact()));
        evidenceRecords.save(bundle.evidence());
        entityManager.flush();
        fragments.saveAllAndFlush(bundle.parsed().fragments().stream().map(this::toFragmentEntity).toList());
        ExtractionJpaModels.ExtractionRunEntity run = runs.saveAndFlush(toRunEntity(bundle.run()));
        if (bundle.review() != null) {
            ExtractionJpaModels.ReviewItemEntity review = reviews.saveAndFlush(toReviewEntity(bundle.review()));
            issues.saveAllAndFlush(bundle.review().issues().stream().map(this::toIssueEntity).toList());
            return new PersistedExtraction(toRun(run), Optional.of(review.id));
        }
        return new PersistedExtraction(toRun(run), Optional.empty());
    }

    @Transactional(readOnly = true)
    public PersistedExtraction findExtractionById(UUID id) {
        return toPersisted(runs.findById(id).orElseThrow(() ->
            new ExtractionExceptions.ExtractionNotFoundException("Extraction not found: " + id)));
    }

    @Transactional(readOnly = true)
    public ReviewDetails findReviewById(UUID id) {
        ExtractionJpaModels.ReviewItemEntity review = reviews.findById(id).orElseThrow(() ->
            new ExtractionExceptions.ReviewNotFoundException("Review not found: " + id));
        return toDetails(review);
    }

    @Transactional(readOnly = true)
    public ReviewPage findPage(ReviewStatus status, int page, int size) {
        var result = reviews.findByStatusOrderByCreatedAtAsc(status, PageRequest.of(page, size));
        List<ReviewItem> items = result.getContent().stream()
            .map(review -> toReviewItem(review, requireRun(review.extractionRunId)))
            .toList();
        return new ReviewPage(items, page, size, result.getTotalElements());
    }

    @Transactional
    public ReviewDetails apply(ReviewResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        try {
            ExtractionJpaModels.ReviewItemEntity review = reviews.findById(resolution.current().id())
                .orElseThrow(() -> new ExtractionExceptions.ReviewNotFoundException(
                    "Review not found: " + resolution.current().id()));
            if (review.status != ReviewStatus.PENDING) {
                throw conflict("Review is already resolved");
            }
            if (review.version != resolution.action().expectedVersion()) {
                throw conflict("Review version conflict: expected " + resolution.action().expectedVersion()
                    + " but was " + review.version);
            }
            entityManager.lock(review, LockModeType.OPTIMISTIC_FORCE_INCREMENT);

            actions.save(toActionEntity(resolution.action()));
            boolean resolves = resolution.action().decision() != ReviewDecision.NEED_MORE_EVIDENCE;
            if (resolves) {
                review.status = ReviewStatus.RESOLVED;
                review.resolvedAt = resolution.action().actedAt();
            }

            ExtractionJpaModels.ExtractionRunEntity run = requireRun(review.extractionRunId);
            run.proposedPayload = toJson(resolution.resolvedProposal());
            switch (resolution.action().decision()) {
                case CONFIRM, CORRECT -> run.status = DataQualityStatus.VERIFIED;
                case REJECT -> run.status = DataQualityStatus.REJECTED;
                case NEED_MORE_EVIDENCE -> run.status = DataQualityStatus.REVIEW_REQUIRED;
            }
            if (resolves) run.completedAt = resolution.action().actedAt();
            runs.save(run);
            reviews.saveAndFlush(review);
            return withVersion(toDetails(review), resolution.action().expectedVersion() + 1);
        } catch (OptimisticLockingFailureException exception) {
            var conflict = new ExtractionExceptions.ReviewConflictException(
                "Review was changed by another request");
            conflict.initCause(exception);
            throw conflict;
        }
    }

    private PersistedExtraction toPersisted(ExtractionJpaModels.ExtractionRunEntity run) {
        return new PersistedExtraction(toRun(run), reviews.findByExtractionRunId(run.id).map(review -> review.id));
    }

    private ReviewDetails toDetails(ExtractionJpaModels.ReviewItemEntity review) {
        ExtractionJpaModels.ExtractionRunEntity run = requireRun(review.extractionRunId);
        return new ReviewDetails(
            toReviewItem(review, run),
            toRun(run),
            fragments.findByEvidenceIdOrderByCreatedAtAsc(run.evidenceId).stream()
                .map(this::toFragment)
                .toList());
    }

    private static ReviewDetails withVersion(ReviewDetails details, long version) {
        ReviewItem item = details.item();
        ReviewItem versioned = new ReviewItem(
            item.id(), item.extractionRunId(), item.status(), version, item.proposal(),
            item.issues(), item.actions(), item.createdAt(), item.resolvedAt());
        return new ReviewDetails(versioned, details.run(), details.fragments());
    }

    private ReviewItem toReviewItem(
        ExtractionJpaModels.ReviewItemEntity review,
        ExtractionJpaModels.ExtractionRunEntity run
    ) {
        return new ReviewItem(
            review.id, review.extractionRunId, review.status, review.version,
            toProposal(run.proposedPayload),
            issues.findByReviewItemIdOrderByIdAsc(review.id).stream().map(this::toIssue).toList(),
            actions.findByReviewItemIdOrderByActedAtAsc(review.id).stream().map(this::toAction).toList(),
            review.createdAt, review.resolvedAt);
    }

    private ExtractionJpaModels.ExtractionRunEntity requireRun(UUID id) {
        return runs.findById(id).orElseThrow(() ->
            new ExtractionExceptions.ExtractionNotFoundException("Extraction not found: " + id));
    }

    private ExtractionJpaModels.SourceArtifactEntity toArtifactEntity(SourceArtifact artifact) {
        var entity = new ExtractionJpaModels.SourceArtifactEntity();
        entity.id = artifact.id();
        entity.sha256 = artifact.sha256();
        entity.mediaType = artifact.mediaType();
        entity.sizeBytes = artifact.sizeBytes();
        entity.storageUri = artifact.storageUri();
        entity.capturedAt = artifact.capturedAt();
        return entity;
    }

    private ExtractionJpaModels.EvidenceFragmentEntity toFragmentEntity(EvidenceFragment fragment) {
        var entity = new ExtractionJpaModels.EvidenceFragmentEntity();
        entity.id = fragment.id();
        entity.evidenceId = fragment.evidenceId();
        entity.locatorType = fragment.locatorType();
        entity.locator = new LinkedHashMap<>(fragment.locator());
        entity.verbatimText = fragment.verbatimText();
        entity.contentHash = fragment.contentHash();
        entity.createdAt = fragment.createdAt();
        return entity;
    }

    private EvidenceFragment toFragment(ExtractionJpaModels.EvidenceFragmentEntity entity) {
        return new EvidenceFragment(
            entity.id, entity.evidenceId, entity.locatorType, entity.locator,
            entity.verbatimText, entity.contentHash, entity.createdAt);
    }

    private ExtractionJpaModels.ExtractionRunEntity toRunEntity(ExtractionRun run) {
        var entity = new ExtractionJpaModels.ExtractionRunEntity();
        entity.id = run.id();
        entity.evidenceId = run.evidenceId();
        entity.organizationId = run.organizationId();
        entity.recruitmentEventId = run.recruitmentEventId();
        entity.inputFingerprint = run.inputFingerprint();
        entity.sourceType = run.sourceType();
        entity.parserName = run.parserName();
        entity.parserVersion = run.parserVersion();
        entity.extractorName = run.extractorName();
        entity.extractorVersion = run.extractorVersion();
        entity.modelName = run.modelName();
        entity.promptVersion = run.promptVersion();
        entity.schemaVersion = run.schemaVersion();
        entity.status = run.status();
        entity.confidence = run.confidence();
        entity.proposedPayload = toJson(run.proposedPayload());
        entity.modelResponse = run.modelResponse();
        entity.errorCode = run.errorCode();
        entity.errorMessage = run.errorMessage();
        entity.startedAt = run.startedAt();
        entity.completedAt = run.completedAt();
        return entity;
    }

    private ExtractionRun toRun(ExtractionJpaModels.ExtractionRunEntity entity) {
        return new ExtractionRun(
            entity.id, entity.evidenceId, entity.organizationId, entity.recruitmentEventId,
            entity.inputFingerprint, entity.sourceType, entity.parserName, entity.parserVersion,
            entity.extractorName, entity.extractorVersion, entity.modelName, entity.promptVersion,
            entity.schemaVersion, entity.status, entity.confidence, toProposal(entity.proposedPayload),
            entity.modelResponse, entity.errorCode, entity.errorMessage, entity.startedAt, entity.completedAt);
    }

    private ExtractionJpaModels.ReviewItemEntity toReviewEntity(ReviewItem review) {
        var entity = new ExtractionJpaModels.ReviewItemEntity();
        entity.id = review.id();
        entity.extractionRunId = review.extractionRunId();
        entity.status = review.status();
        entity.version = review.version();
        entity.createdAt = review.createdAt();
        entity.resolvedAt = review.resolvedAt();
        return entity;
    }

    private ExtractionJpaModels.ReviewIssueEntity toIssueEntity(ReviewIssue issue) {
        var entity = new ExtractionJpaModels.ReviewIssueEntity();
        entity.id = issue.id();
        entity.reviewItemId = issue.reviewItemId();
        entity.reasonCode = issue.reasonCode();
        entity.fieldPath = issue.fieldPath();
        entity.message = issue.message();
        entity.evidenceFragmentId = issue.evidenceFragmentId();
        return entity;
    }

    private ReviewIssue toIssue(ExtractionJpaModels.ReviewIssueEntity entity) {
        return new ReviewIssue(
            entity.id, entity.reviewItemId, entity.reasonCode, entity.fieldPath,
            entity.message, entity.evidenceFragmentId);
    }

    private ExtractionJpaModels.ReviewActionEntity toActionEntity(ReviewAction action) {
        var entity = new ExtractionJpaModels.ReviewActionEntity();
        entity.id = action.id();
        entity.reviewItemId = action.reviewItemId();
        entity.decision = action.decision();
        entity.expectedVersion = action.expectedVersion();
        entity.originalPayload = toJson(action.originalPayload());
        entity.correctedPayload = action.correctedPayload().isEmpty() ? null : toJson(action.correctedPayload());
        entity.note = action.note();
        entity.actedAt = action.actedAt();
        return entity;
    }

    private ReviewAction toAction(ExtractionJpaModels.ReviewActionEntity entity) {
        return new ReviewAction(
            entity.id, entity.reviewItemId, entity.decision, entity.expectedVersion,
            toMap(entity.originalPayload), toMap(entity.correctedPayload), entity.note, entity.actedAt);
    }

    private JsonNode toJson(Object value) {
        return json.valueToTree(value);
    }

    private RecruitmentExtractionProposal toProposal(JsonNode value) {
        try {
            return json.treeToValue(value, RecruitmentExtractionProposal.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored extraction proposal cannot be read", exception);
        }
    }

    private Map<String, Object> toMap(JsonNode value) {
        return value == null || value.isNull() ? Map.of() : json.convertValue(value, MAP_TYPE);
    }

    private static ExtractionExceptions.ReviewConflictException conflict(String message) {
        return new ExtractionExceptions.ReviewConflictException(message);
    }
}
