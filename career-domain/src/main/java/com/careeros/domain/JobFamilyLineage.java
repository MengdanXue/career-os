package com.careeros.domain;

import com.careeros.domain.DomainEnums.JobFamily;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 同一单位下同一类岗位的跨年度出现记录（产品需求 §5 OpportunityHistory）。
 *
 * <p>岗位族由 {@code lineageKey} 归并——单位名 + 技术族 + 岗位名称，全部归一化后拼接。
 * 这个键刻意保守：岗位名称在年度之间稍有改动就会分成两条族谱，宁可少归并也不把不同岗位
 * 拼成一条"连续招聘"的假象。归并不足只会让再现信号偏弱，归并过度则会凭空造出趋势。
 */
public record JobFamilyLineage(
    String lineageKey,
    UUID organizationId,
    String organizationName,
    String representativeTitle,
    JobFamily jobFamily,
    SortedSet<Integer> observedYears,
    List<UUID> jobPostingIds,
    List<UUID> evidenceIds
) {
    public JobFamilyLineage {
        if (lineageKey == null || lineageKey.isBlank()) throw new IllegalArgumentException("lineageKey is required");
        Objects.requireNonNull(organizationId, "organizationId");
        if (organizationName == null || organizationName.isBlank()) {
            throw new IllegalArgumentException("organizationName is required");
        }
        if (representativeTitle == null || representativeTitle.isBlank()) {
            throw new IllegalArgumentException("representativeTitle is required");
        }
        Objects.requireNonNull(jobFamily, "jobFamily");
        observedYears = observedYears == null || observedYears.isEmpty()
            ? new TreeSet<>()
            : java.util.Collections.unmodifiableSortedSet(new TreeSet<>(observedYears));
        if (observedYears.isEmpty()) throw new IllegalArgumentException("a lineage needs at least one observed year");
        jobPostingIds = jobPostingIds == null ? List.of() : List.copyOf(jobPostingIds);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public int firstObservedYear() { return observedYears.first(); }
    public int lastObservedYear() { return observedYears.last(); }

    /** 观测到的年份是否覆盖了首末年之间的每一年。 */
    public boolean isContiguous() {
        return observedYears.size() == lastObservedYear() - firstObservedYear() + 1;
    }
}
