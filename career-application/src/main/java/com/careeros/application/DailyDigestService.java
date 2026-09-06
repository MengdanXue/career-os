package com.careeros.application;

import com.careeros.domain.DailyDigest;
import com.careeros.domain.DailyDigestBuilder;
import com.careeros.domain.DailyDigestBuilder.DigestInput;
import com.careeros.domain.JobPosting;
import com.careeros.domain.Opportunity;
import com.careeros.domain.RecruitmentEvent;
import com.careeros.application.JobUpsertService.JobChange;
import com.careeros.application.JobUpsertService.JobUpsertResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 把一次增量入库的结果变成当天的摘要（产品需求 §8 每日增量监控）。
 *
 * <p>报名截止日来自岗位所属的 RecruitmentEvent，策略等级来自已有的 Opportunity——两者都是
 * 已经算好的结论，这里只做汇集，不重新判断。缺少任一项时该字段留空，不猜。
 */
public final class DailyDigestService {
    private final RepositoryPorts.JobPostings jobs;
    private final RepositoryPorts.RecruitmentEvents events;
    private final RepositoryPorts.Organizations organizations;
    private final RepositoryPorts.Opportunities opportunities;
    private final DailyDigestBuilder builder;

    public DailyDigestService(
        RepositoryPorts.JobPostings jobs,
        RepositoryPorts.RecruitmentEvents events,
        RepositoryPorts.Organizations organizations,
        RepositoryPorts.Opportunities opportunities,
        DailyDigestBuilder builder
    ) {
        this.jobs = Objects.requireNonNull(jobs);
        this.events = Objects.requireNonNull(events);
        this.organizations = Objects.requireNonNull(organizations);
        this.opportunities = Objects.requireNonNull(opportunities);
        this.builder = Objects.requireNonNull(builder);
    }

    public DailyDigest digestFor(JobUpsertResult result, LocalDate reportDate) {
        return digestFor(result, reportDate, DailyDigestBuilder.DEFAULT_DEADLINE_WINDOW_DAYS);
    }

    public DailyDigest digestFor(JobUpsertResult result, LocalDate reportDate, int deadlineWindowDays) {
        Objects.requireNonNull(result, "result");
        Map<UUID, JobPosting> jobsById = new LinkedHashMap<>();
        jobs.findAll().forEach(job -> jobsById.put(job.id(), job));
        Map<UUID, RecruitmentEvent> eventsById = new LinkedHashMap<>();
        events.findAll().forEach(event -> eventsById.put(event.id(), event));
        Map<UUID, String> organizationNames = new LinkedHashMap<>();
        organizations.findAll().forEach(value -> organizationNames.put(value.id(), value.name()));
        Map<UUID, Opportunity> opportunityByJob = new LinkedHashMap<>();
        opportunities.findAll().forEach(value -> opportunityByJob.put(value.jobPostingId(), value));

        var inputs = new ArrayList<DigestInput>();
        for (JobChange change : result.changes()) {
            JobPosting job = jobsById.get(change.jobPostingId());
            if (job == null) continue;
            RecruitmentEvent event = eventsById.get(job.recruitmentEventId());
            Opportunity opportunity = opportunityByJob.get(job.id());
            inputs.add(new DigestInput(
                job.id(), job.title(),
                organizationNames.get(job.organizationId()),
                event == null ? null : event.applicationEndsOn(),
                change.kind(),
                opportunity == null ? null : opportunity.scorecard().strategyGrade()));
        }
        return builder.build(inputs, reportDate, deadlineWindowDays);
    }

    /** 供只关心"今天有哪些变化"的调用方使用的空结果。 */
    public static DailyDigest empty(LocalDate reportDate) {
        return new DailyDigest(reportDate, List.of(), 0, DailyDigestBuilder.DEFAULT_DEADLINE_WINDOW_DAYS);
    }
}
