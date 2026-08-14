package com.careeros.domain;

import com.careeros.domain.DomainEnums.ReviewDecision;
import com.careeros.domain.DomainEnums.ReviewStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ReviewItem(
    UUID id,
    UUID extractionRunId,
    ReviewStatus status,
    long version,
    RecruitmentExtractionProposal proposal,
    List<ReviewIssue> issues,
    List<ReviewAction> actions,
    Instant createdAt,
    Instant resolvedAt
) {
    public ReviewItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(extractionRunId, "extractionRunId");
        Objects.requireNonNull(status, "status");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        Objects.requireNonNull(proposal, "proposal");
        issues = issues == null ? List.of() : List.copyOf(issues);
        actions = actions == null ? List.of() : List.copyOf(actions);
        Objects.requireNonNull(createdAt, "createdAt");
        if (status == ReviewStatus.RESOLVED && resolvedAt == null) {
            throw new IllegalArgumentException("resolved review requires resolvedAt");
        }
        if (status == ReviewStatus.PENDING && resolvedAt != null) {
            throw new IllegalArgumentException("pending review must not have resolvedAt");
        }
    }

    public ReviewItem apply(ReviewAction action) {
        Objects.requireNonNull(action, "action");
        if (status != ReviewStatus.PENDING) throw new IllegalStateException("Review is already resolved");
        if (!id.equals(action.reviewItemId())) throw new IllegalArgumentException("action belongs to another review");
        if (version != action.expectedVersion()) {
            throw new IllegalStateException("Review version conflict: expected " + action.expectedVersion() + " but was " + version);
        }
        List<ReviewAction> changedActions = new ArrayList<>(actions);
        changedActions.add(action);
        boolean resolves = action.decision() != ReviewDecision.NEED_MORE_EVIDENCE;
        return new ReviewItem(
            id, extractionRunId, resolves ? ReviewStatus.RESOLVED : ReviewStatus.PENDING,
            version + 1, proposal, issues, changedActions, createdAt,
            resolves ? action.actedAt() : null);
    }
}
