package com.careeros.application;

import com.careeros.domain.DailyDigest;
import com.careeros.domain.DailyDigestBuilder;
import com.careeros.domain.DailyDigestBuilder.DigestInput;
import com.careeros.domain.JobPosting;
import com.careeros.domain.RecruitmentEvent;
import com.careeros.application.JobUpsertService.JobChange;
import com.careeros.application.JobUpsertService.JobUpsertResult;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 把一次增量入库的结果变成当天的摘要（产品需求 §8 每日增量监控）。
 *
 * <p>报名截止日来自岗位所属的 RecruitmentEvent，是已经算好的结论，这里只做汇集，不重新判断；
 * 缺少时该字段留空，不猜。
 *
 * <p>摘要不带推荐等级：主干把它放在候选人维度的 DecisionAssessment 上，而这里报的是岗位变化，
 * 与具体候选人无关。把两者合起来需要给摘要引入候选人参数，属于独立的设计改动。
 */
public final class DailyDigestService {
    private final RepositoryPorts.JobPostings jobs;
    private final RepositoryPorts.RecruitmentEvents events;
    private final RepositoryPorts.Organizations organizations;
    private final DailyDigestBuilder builder;

    public DailyDigestService(
        RepositoryPorts.JobPostings jobs,
        RepositoryPorts.RecruitmentEvents events,
        RepositoryPorts.Organizations organizations,
        DailyDigestBuilder builder
    ) {
        this.jobs = Objects.requireNonNull(jobs);
        this.events = Objects.requireNonNull(events);
        this.organizations = Objects.requireNonNull(organizations);
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

        // 逐岗去重后再交给 builder。DailyDigest 规定一个岗位在一份摘要里最多出现一次，
        // 违反时构造器直接抛异常——而摘要是在导入提交之后才生成的，真抛了就会把一次
        // 已经成功的导入报成 500。当前入库路径产不出重复 id，但这个不变式不该由调用方
        // 的正确性来兜底。同一岗位若出现多条变化，以最后一条为准：它反映的是最终状态。
        Map<UUID, DigestInput> inputsByJob = new LinkedHashMap<>();
        for (JobChange change : result.changes()) {
            JobPosting job = jobsById.get(change.jobPostingId());
            if (job == null) continue;
            RecruitmentEvent event = eventsById.get(job.recruitmentEventId());
            inputsByJob.put(job.id(), new DigestInput(
                job.id(), job.title(),
                organizationNames.get(job.organizationId()),
                event == null ? null : event.applicationEndsOn(),
                change.kind()));
        }
        return builder.build(List.copyOf(inputsByJob.values()), reportDate, deadlineWindowDays);
    }

    /** 供只关心"今天有哪些变化"的调用方使用的空结果。 */
    public static DailyDigest empty(LocalDate reportDate) {
        return new DailyDigest(reportDate, List.of(), 0, DailyDigestBuilder.DEFAULT_DEADLINE_WINDOW_DAYS);
    }
}
