package com.careeros.application;

import com.careeros.domain.*;
import com.careeros.domain.DomainEnums.*;
import com.careeros.application.JobUpsertService.JobChange;
import com.careeros.application.JobUpsertService.JobUpsertResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DailyDigestServiceTest {
    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 11);

    private final List<JobPosting> jobs = new ArrayList<>();
    private final List<RecruitmentEvent> events = new ArrayList<>();

    private final DailyDigestService service = new DailyDigestService(
        new JobRepository(jobs), new EventRepository(events),
        new OrganizationRepository(new ArrayList<>(List.of(new Organization(ORGANIZATION_ID, "杭州市儿童医院",
            OrganizationType.HOSPITAL, null, "浙江", "杭州", null, null, null)))),
        new DailyDigestBuilder());

    @Test void changesAreProjectedOntoTheStoredJobsAndTheirDeadlines() {
        UUID added = addPosting("信息中心工作人员", TODAY.plusDays(3));
        UUID untouched = addPosting("数据平台工程师", TODAY.plusMonths(6));

        var digest = service.digestFor(result(
            new JobChange(added, JobChangeKind.NEW),
            new JobChange(untouched, JobChangeKind.UNCHANGED)), TODAY);

        assertThat(digest.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.jobPostingId()).isEqualTo(added);
            assertThat(entry.reason()).isEqualTo(DigestReason.NEW);
            assertThat(entry.organizationName()).isEqualTo("杭州市儿童医院");
            assertThat(entry.daysUntilDeadline()).isEqualTo(3);
        });
        // 未变化且截止日期还远的那条只进计数，不进推送列表。
        assertThat(digest.suppressedUnchangedCount()).isEqualTo(1);
    }

    /**
     * 摘要是在导入提交之后才生成的。DailyDigest 规定一个岗位最多出现一次，违反时构造器
     * 直接抛异常——那会把一次已经成功的导入报成 500。重复必须在这里被吸收掉。
     */
    @Test void duplicateChangesForOneJobCannotFailAnAlreadyCommittedImport() {
        UUID jobId = addPosting("信息中心工作人员", TODAY.plusDays(2));

        var digest = service.digestFor(result(
            new JobChange(jobId, JobChangeKind.NEW),
            new JobChange(jobId, JobChangeKind.DEACTIVATED)), TODAY);

        assertThat(digest.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.jobPostingId()).isEqualTo(jobId);
            // 以最后一条为准：它反映的是该岗位在本次入库后的最终状态。
            assertThat(entry.reason()).isEqualTo(DigestReason.DEACTIVATED);
        });
    }

    /** 变化指向一个查不到的岗位时跳过，不用占位数据凑一条摘要。 */
    @Test void changesForUnknownJobsAreSkipped() {
        var digest = service.digestFor(result(new JobChange(UUID.randomUUID(), JobChangeKind.NEW)), TODAY);
        assertThat(digest.isEmpty()).isTrue();
    }

    private static JobUpsertResult result(JobChange... changes) {
        return new JobUpsertResult(0, 0, 0, 0, List.of(), List.of(changes));
    }

    private UUID addPosting(String title, LocalDate applicationEndsOn) {
        UUID eventId = UUID.randomUUID();
        events.add(new RecruitmentEvent(eventId, "2026年公开招聘", 2026, EventType.PUBLIC_INSTITUTION,
            null, null, applicationEndsOn, "https://example.test/" + title,
            EmploymentType.ESTABLISHMENT, List.of()));
        JobPosting job = new JobPosting(UUID.randomUUID(), eventId, ORGANIZATION_ID, null, title,
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, "杭州", 1,
            EducationLevel.MASTER, Set.of(), Set.of(), null, LocalDate.of(2026, 1, 1), null,
            Set.of(), null, "https://example.test/job/" + title, List.of(UUID.randomUUID()));
        jobs.add(job);
        return job.id();
    }

    private abstract static class InMemory<T> {
        final List<T> items;
        final java.util.function.Function<T, UUID> id;
        InMemory(List<T> items, java.util.function.Function<T, UUID> id) { this.items = items; this.id = id; }
        public T save(T aggregate) { items.add(aggregate); return aggregate; }
        public Optional<T> findById(UUID value) {
            return items.stream().filter(item -> id.apply(item).equals(value)).findFirst();
        }
        public List<T> findAll() { return List.copyOf(items); }
        public void deleteById(UUID value) { items.removeIf(item -> id.apply(item).equals(value)); }
    }

    private static final class JobRepository extends InMemory<JobPosting> implements RepositoryPorts.JobPostings {
        JobRepository(List<JobPosting> items) { super(items, JobPosting::id); }
    }

    private static final class EventRepository extends InMemory<RecruitmentEvent> implements RepositoryPorts.RecruitmentEvents {
        EventRepository(List<RecruitmentEvent> items) { super(items, RecruitmentEvent::id); }
    }

    private static final class OrganizationRepository extends InMemory<Organization> implements RepositoryPorts.Organizations {
        OrganizationRepository(List<Organization> items) { super(items, Organization::id); }
    }
}
