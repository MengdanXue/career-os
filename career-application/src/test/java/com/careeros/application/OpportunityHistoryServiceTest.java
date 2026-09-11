package com.careeros.application;

import com.careeros.domain.*;
import com.careeros.domain.DomainEnums.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OpportunityHistoryServiceTest {
    private static final UUID ORGANIZATION_ID = UUID.randomUUID();

    private final List<JobPosting> jobs = new ArrayList<>();
    private final List<RecruitmentEvent> events = new ArrayList<>();

    private final OpportunityHistoryService service = new OpportunityHistoryService(
        new JobRepository(jobs), new EventRepository(events),
        new OrganizationRepository(new ArrayList<>(List.of(new Organization(ORGANIZATION_ID, "杭州市儿童医院",
            OrganizationType.HOSPITAL, null, "浙江", "杭州", null, null, null)))),
        new JobLineageBuilder(), new OpportunityForecaster());

    @Test void emptyDataProducesAnEmptyWatchlistRatherThanFailing() {
        assertThat(service.lineages()).isEmpty();
        assertThat(service.watchlist(2027)).isEmpty();
        assertThat(service.forecastFor(UUID.randomUUID(), 2027)).isEmpty();
    }

    /** 只导入了一年数据这件事必须如实反映，不能被读成"没有再现规律"。 */
    @Test void oneYearOfDataYieldsInsufficientHistoryNotAnAbsentPattern() {
        addPosting(2026, "信息中心工作人员");

        var watchlist = service.watchlist(2027);
        assertThat(watchlist).hasSize(1);
        assertThat(watchlist.getFirst().signal()).isEqualTo(RecurrenceSignal.INSUFFICIENT_HISTORY);
        assertThat(watchlist.getFirst().observationWindowSpan()).isEqualTo(1);
    }

    @Test void twoConsecutiveYearsBecomeARecurringSignalTiedToTheJob() {
        addPosting(2025, "信息中心工作人员");
        UUID latest = addPosting(2026, "信息中心工作人员");

        var forecast = service.forecastFor(latest, 2027);
        assertThat(forecast).isPresent();
        assertThat(forecast.orElseThrow().signal()).isEqualTo(RecurrenceSignal.RECURRING_ANNUAL);
        assertThat(forecast.orElseThrow().observedYears()).containsExactly(2025, 2026);
    }

    /** 观测窗口取全部已落库数据的年度范围，而不是单条族谱自己的范围。 */
    @Test void observationWindowSpansAllLoadedYears() {
        addPosting(2024, "其他岗位");
        addPosting(2026, "信息中心工作人员");

        var forecast = service.watchlist(2027).stream()
            .filter(value -> value.observedYears().contains(2026) && value.observedYears().size() == 1)
            .findFirst().orElseThrow();
        assertThat(forecast.observationWindowStartYear()).isEqualTo(2024);
        assertThat(forecast.observationWindowEndYear()).isEqualTo(2026);
        assertThat(forecast.signal()).isEqualTo(RecurrenceSignal.SINGLE_OCCURRENCE);
    }

    /** 岗位所属事件缺失时跳过，不拿占位年份凑数。 */
    @Test void postingsWithoutTheirEventAreSkipped() {
        jobs.add(posting(UUID.randomUUID(), "孤立岗位"));
        assertThat(service.lineages()).isEmpty();
    }

    private UUID addPosting(int year, String title) {
        UUID eventId = UUID.randomUUID();
        events.add(new RecruitmentEvent(eventId, year + "年公开招聘", year, EventType.PUBLIC_INSTITUTION,
            null, null, null, "https://example.test/" + year + "/" + title,
            EmploymentType.ESTABLISHMENT, List.of()));
        JobPosting job = posting(eventId, title);
        jobs.add(job);
        return job.id();
    }

    private JobPosting posting(UUID eventId, String title) {
        return new JobPosting(UUID.randomUUID(), eventId, ORGANIZATION_ID, null, title,
            JobFamily.INFORMATION_SYSTEMS, EmploymentType.ESTABLISHMENT, "杭州", 1,
            EducationLevel.MASTER, Set.of(), Set.of(), null, LocalDate.of(2026, 1, 1), null,
            Set.of(), null, "https://example.test/job", List.of(UUID.randomUUID()));
    }

    /** 内存仓库：只有 findAll 参与建族，其余方法按接口补齐。 */
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
