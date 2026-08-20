package com.careeros.application;

import static com.careeros.application.DecisionPorts.*;
import static com.careeros.domain.DomainEnums.*;

import com.careeros.application.JobAdmissionPorts.JobAdmissions;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class DecisionRankingService {
    private final JobContexts jobContexts;
    private final JobAdmissions admissions;
    private final DecisionAssessor assessor;

    public DecisionRankingService(JobContexts jobContexts, JobAdmissions admissions, DecisionAssessor assessor) {
        this.jobContexts = jobContexts;
        this.admissions = admissions;
        this.assessor = assessor;
    }

    public RankingPage rank(UUID candidateId, RankingQuery query, Instant now) {
        if (query.page() < 0) throw new IllegalArgumentException("page must not be negative");
        if (query.size() < 1 || query.size() > 100) throw new IllegalArgumentException("size must be between 1 and 100");
        Stream<DecisionBundle> stream = jobContexts.findActive().stream()
            .filter(context -> admissions.findByJobId(context.job().id())
                .map(admission -> admission.admitted())
                .orElse(false))
            .filter(context -> query.location() == null || contains(context.job().location(), query.location()))
            .filter(context -> query.jobFamily() == null || context.job().jobFamily() == query.jobFamily())
            .map(context -> assessor.assess(candidateId, context.job().id(), now))
            .filter(bundle -> query.includeExcluded() || bundle.decision().tier() != OpportunityTier.EXCLUDED)
            .filter(bundle -> query.tier() == null || bundle.decision().tier() == query.tier());
        List<DecisionBundle> ordered = stream.sorted(order()).toList();
        int from = (int) Math.min((long) query.page() * query.size(), ordered.size());
        int to = Math.min(from + query.size(), ordered.size());
        return new RankingPage(ordered.subList(from, to), query.page(), query.size(), ordered.size());
    }

    private static Comparator<DecisionBundle> order() {
        return Comparator.<DecisionBundle>comparingInt(value -> value.decision().tier().ordinal())
            .thenComparing(Comparator.comparingInt((DecisionBundle value) -> value.decision().fitScore()).reversed())
            .thenComparing(Comparator.comparingInt((DecisionBundle value) -> value.decision().stabilityScore()).reversed())
            .thenComparing(Comparator.comparingInt((DecisionBundle value) -> value.decision().coveragePercent()).reversed())
            .thenComparing(value -> value.jobContext().event().applicationEndsOn(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(value -> value.decision().jobPostingId());
    }

    private static boolean contains(String value, String query) { return value != null && value.toLowerCase().contains(query.toLowerCase()); }

    public record RankingQuery(OpportunityTier tier, String location, JobFamily jobFamily, int page, int size, boolean includeExcluded) {}
    public record RankingPage(List<DecisionBundle> items, int page, int size, long total) { public RankingPage { items = List.copyOf(items); } }
}
