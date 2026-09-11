package com.careeros.application;

import com.careeros.domain.JobFamilyLineage;
import com.careeros.domain.JobLineageBuilder;
import com.careeros.domain.JobLineageBuilder.LineageInput;
import com.careeros.domain.JobPosting;
import com.careeros.domain.OpportunityForecast;
import com.careeros.domain.OpportunityForecaster;
import com.careeros.domain.RecruitmentEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 把已落库的岗位按招聘年度归并成岗位族，并给出目标年度的再现信号（产品需求 §5、§10.10）。
 *
 * <p>岗位族是 job_posting 与 recruitment_event 上的一个投影，不单独建表——它完全由这两张表
 * 推导得出，存一份只会引入不一致。观测窗口取已落库数据实际覆盖的年度范围，因此"只导入了
 * 一年数据"这件事会如实反映成 INSUFFICIENT_HISTORY，而不是被当成"没有再现规律"。
 */
public final class OpportunityHistoryService {
    private final RepositoryPorts.JobPostings jobs;
    private final RepositoryPorts.RecruitmentEvents events;
    private final RepositoryPorts.Organizations organizations;
    private final JobLineageBuilder lineageBuilder;
    private final OpportunityForecaster forecaster;

    public OpportunityHistoryService(
        RepositoryPorts.JobPostings jobs,
        RepositoryPorts.RecruitmentEvents events,
        RepositoryPorts.Organizations organizations,
        JobLineageBuilder lineageBuilder,
        OpportunityForecaster forecaster
    ) {
        this.jobs = Objects.requireNonNull(jobs);
        this.events = Objects.requireNonNull(events);
        this.organizations = Objects.requireNonNull(organizations);
        this.lineageBuilder = Objects.requireNonNull(lineageBuilder);
        this.forecaster = Objects.requireNonNull(forecaster);
    }

    public List<JobFamilyLineage> lineages() {
        return lineageBuilder.build(inputs());
    }

    /** 目标年度的关注清单：每条岗位族一个再现信号。 */
    public List<OpportunityForecast> watchlist(int targetYear) {
        List<LineageInput> inputs = inputs();
        if (inputs.isEmpty()) return List.of();
        int windowStart = inputs.stream().mapToInt(LineageInput::recruitmentYear).min().orElseThrow();
        int windowEnd = inputs.stream().mapToInt(LineageInput::recruitmentYear).max().orElseThrow();
        return lineageBuilder.build(inputs).stream()
            .map(lineage -> forecaster.forecast(lineage, targetYear, windowStart, windowEnd))
            .toList();
    }

    /** 单个岗位所属族的再现信号；岗位不在任何族里（数据不全）时返回空。 */
    public Optional<OpportunityForecast> forecastFor(UUID jobPostingId, int targetYear) {
        Objects.requireNonNull(jobPostingId, "jobPostingId");
        return watchlistByJob(targetYear).map(byJob -> byJob.get(jobPostingId));
    }

    private Optional<Map<UUID, OpportunityForecast>> watchlistByJob(int targetYear) {
        List<LineageInput> inputs = inputs();
        if (inputs.isEmpty()) return Optional.empty();
        int windowStart = inputs.stream().mapToInt(LineageInput::recruitmentYear).min().orElseThrow();
        int windowEnd = inputs.stream().mapToInt(LineageInput::recruitmentYear).max().orElseThrow();
        var byJob = new LinkedHashMap<UUID, OpportunityForecast>();
        for (JobFamilyLineage lineage : lineageBuilder.build(inputs)) {
            OpportunityForecast forecast = forecaster.forecast(lineage, targetYear, windowStart, windowEnd);
            lineage.jobPostingIds().forEach(id -> byJob.put(id, forecast));
        }
        return Optional.of(byJob);
    }

    /** 岗位缺少所属事件或单位时直接跳过，不用占位数据凑出一个年份。 */
    private List<LineageInput> inputs() {
        Map<UUID, RecruitmentEvent> eventsById = new LinkedHashMap<>();
        events.findAll().forEach(event -> eventsById.put(event.id(), event));
        Map<UUID, String> organizationNames = new LinkedHashMap<>();
        organizations.findAll().forEach(organization -> organizationNames.put(organization.id(), organization.name()));

        var inputs = new ArrayList<LineageInput>();
        for (JobPosting job : jobs.findAll()) {
            RecruitmentEvent event = eventsById.get(job.recruitmentEventId());
            String organizationName = organizationNames.get(job.organizationId());
            if (event == null || organizationName == null) continue;
            inputs.add(new LineageInput(job, event.recruitmentYear(), organizationName));
        }
        return inputs;
    }
}
