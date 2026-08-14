package com.careeros.agent.domain;

import com.careeros.agent.domain.EligibilityAssessment.OverallStatus;
import com.careeros.agent.domain.OpportunityTierAssessment.OpportunityTier;

import java.time.OffsetDateTime;
import java.util.List;

public record IncrementalWatchlist(
        OffsetDateTime generatedAt,
        int added,
        int changed,
        int removed,
        int unchanged,
        List<Item> items
) {
    public IncrementalWatchlist {
        items = List.copyOf(items);
    }

    public enum DeltaType { ADDED, CHANGED, REMOVED }

    public enum Action { APPLY, VERIFY_FIRST, BACKUP, REJECT, REVIEW, REMOVED }

    public record Item(
            DeltaType deltaType,
            String jobId,
            String employer,
            String positionTitle,
            boolean itRelated,
            OpportunityTier tier,
            OverallStatus eligibility,
            Action action,
            String reason
    ) {}
}
