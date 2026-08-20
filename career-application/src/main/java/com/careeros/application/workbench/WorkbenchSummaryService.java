package com.careeros.application.workbench;

import static com.careeros.application.workbench.WorkbenchPorts.*;

import com.careeros.domain.DomainEnums.OpportunityTier;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class WorkbenchSummaryService {
    private final DecisionOverview decisions;
    private final AcquisitionOverview acquisition;
    private final ReviewOverview reviews;
    private final Clock clock;

    public WorkbenchSummaryService(
        DecisionOverview decisions, AcquisitionOverview acquisition, ReviewOverview reviews, Clock clock
    ) {
        this.decisions = Objects.requireNonNull(decisions);
        this.acquisition = Objects.requireNonNull(acquisition);
        this.reviews = Objects.requireNonNull(reviews);
        this.clock = Objects.requireNonNull(clock);
    }

    public WorkbenchSummary summarize(UUID candidateId) {
        Objects.requireNonNull(candidateId, "candidateId");
        var now = clock.instant();
        var decisionSignals = List.copyOf(decisions.load(candidateId, now));
        var counts = new WorkbenchSummary.TierCounts(
            count(decisionSignals, OpportunityTier.T1), count(decisionSignals, OpportunityTier.T2),
            count(decisionSignals, OpportunityTier.T3), count(decisionSignals, OpportunityTier.EXCLUDED));
        LocalDate today = LocalDate.now(clock);
        var deadlines = decisionSignals.stream()
            .filter(value -> value.deadline() != null && !value.deadline().isBefore(today))
            .filter(value -> !value.deadline().isAfter(today.plusDays(45)))
            .sorted(Comparator.comparing(DecisionSignal::deadline).thenComparing(DecisionSignal::jobId))
            .limit(8)
            .map(value -> new WorkbenchSummary.DeadlineItem(
                value.jobId(), value.jobTitle(), value.organizationName(), value.location(),
                value.tier().name(), value.deadline(), ChronoUnit.DAYS.between(today, value.deadline())))
            .toList();

        WorkbenchSummary.ChangesSection changes;
        WorkbenchSummary.SourcesSection sources;
        try {
            var snapshot = acquisition.load(now);
            changes = new WorkbenchSummary.ChangesSection(true, null, snapshot.changes().stream()
                .sorted(Comparator.comparing(ChangeSignal::occurredAt).reversed())
                .limit(10)
                .map(value -> new WorkbenchSummary.ChangeItem(value.id(), value.changeType(), value.sourceUri(), value.jobDeltaSummary(), value.occurredAt()))
                .toList());
            var enabledSources = snapshot.sources().stream().filter(SourceSignal::enabled).toList();
            var issues = enabledSources.stream().filter(WorkbenchSummaryService::isUnhealthy)
                .map(value -> new WorkbenchSummary.SourceIssue(value.id(), value.name(), value.consecutiveFailureCount(), value.lastFailureAt()))
                .toList();
            sources = new WorkbenchSummary.SourcesSection(true, null, enabledSources.size() - issues.size(), enabledSources.size(), issues);
        } catch (RuntimeException exception) {
            changes = new WorkbenchSummary.ChangesSection(false, "岗位变化暂时无法读取", List.of());
            sources = new WorkbenchSummary.SourcesSection(false, "数据源健康暂时无法读取", 0, 0, List.of());
        }

        WorkbenchSummary.ReviewsSection reviewSection;
        try {
            reviewSection = new WorkbenchSummary.ReviewsSection(true, null, reviews.pendingCount());
        } catch (RuntimeException exception) {
            reviewSection = new WorkbenchSummary.ReviewsSection(false, "待复核数据暂时无法读取", 0);
        }
        return new WorkbenchSummary(candidateId, now, counts, deadlines, changes, sources, reviewSection);
    }

    private static long count(List<DecisionSignal> values, OpportunityTier tier) {
        return values.stream().filter(value -> value.tier() == tier).count();
    }

    private static boolean isUnhealthy(SourceSignal source) {
        if (source.consecutiveFailureCount() > 0) return true;
        return source.lastFailureAt() != null && (source.lastSuccessAt() == null || source.lastFailureAt().isAfter(source.lastSuccessAt()));
    }
}
