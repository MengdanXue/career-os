package com.careeros.application.workbench;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WorkbenchSummary(
    UUID candidateId,
    Instant generatedAt,
    TierCounts tierCounts,
    List<DeadlineItem> deadlines,
    ChangesSection changes,
    SourcesSection sources,
    ReviewsSection reviews
) {
    public WorkbenchSummary { deadlines = List.copyOf(deadlines); }

    public record TierCounts(long t1, long t2, long t3, long excluded) {}
    public record DeadlineItem(
        UUID jobId, String jobTitle, String organizationName, String location,
        String tier, LocalDate deadline, long daysRemaining
    ) {}
    public record ChangeItem(
        UUID id, String changeType, URI sourceUri, Map<String,Object> jobDeltaSummary, Instant occurredAt
    ) {}
    public record ChangesSection(boolean available, String message, List<ChangeItem> items) {
        public ChangesSection { items = List.copyOf(items); }
    }
    public record SourceIssue(UUID id, String name, int consecutiveFailureCount, Instant lastFailureAt) {}
    public record SourcesSection(
        boolean available, String message, int healthy, int enabled, List<SourceIssue> issues
    ) { public SourcesSection { issues = List.copyOf(issues); } }
    public record ReviewsSection(boolean available, String message, long pending) {}
}
