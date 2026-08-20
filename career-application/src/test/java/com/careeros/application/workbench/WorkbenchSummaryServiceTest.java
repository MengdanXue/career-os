package com.careeros.application.workbench;

import static com.careeros.application.workbench.WorkbenchPorts.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.domain.DomainEnums.EligibilityStatus;
import com.careeros.domain.DomainEnums.OpportunityTier;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkbenchSummaryServiceTest {
    private static final UUID CANDIDATE_ID = UUID.fromString("01992f09-0000-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void summarizesCurrentDecisionTiersDeadlinesChangesAndReviewWork() {
        DecisionOverview decisions = (candidateId, now) -> List.of(
            decision("信息中心 Java 岗", OpportunityTier.T1, LocalDate.of(2026, 8, 28)),
            decision("高校数据平台岗", OpportunityTier.T2, LocalDate.of(2026, 9, 18)),
            decision("劳务派遣运维岗", OpportunityTier.EXCLUDED, null));
        AcquisitionOverview acquisition = now -> new AcquisitionSnapshot(
            List.of(new ChangeSignal(UUID.randomUUID(), "UPDATED", URI.create("https://example.gov.cn/jobs"), Map.of("updated", 2), NOW.minusSeconds(3600))),
            List.of(new SourceSignal(UUID.randomUUID(), "杭州人社", true, NOW.minusSeconds(7200), null, 0)));
        ReviewOverview reviews = () -> 4;

        var result = new WorkbenchSummaryService(decisions, acquisition, reviews, Clock.fixed(NOW, ZoneOffset.UTC)).summarize(CANDIDATE_ID);

        assertThat(result.tierCounts().t1()).isEqualTo(1);
        assertThat(result.tierCounts().t2()).isEqualTo(1);
        assertThat(result.tierCounts().excluded()).isEqualTo(1);
        assertThat(result.deadlines()).extracting(WorkbenchSummary.DeadlineItem::jobTitle).containsExactly("信息中心 Java 岗", "高校数据平台岗");
        assertThat(result.changes().items()).hasSize(1);
        assertThat(result.sources().healthy()).isEqualTo(1);
        assertThat(result.reviews().pending()).isEqualTo(4);
    }

    @Test
    void keepsDecisionAndReviewSectionsAvailableWhenAcquisitionIsDown() {
        DecisionOverview decisions = (candidateId, now) -> List.of(decision("信息中心 Java 岗", OpportunityTier.T1, null));
        AcquisitionOverview acquisition = now -> { throw new IllegalStateException("database view unavailable"); };

        var result = new WorkbenchSummaryService(decisions, acquisition, () -> 2, Clock.fixed(NOW, ZoneOffset.UTC)).summarize(CANDIDATE_ID);

        assertThat(result.tierCounts().t1()).isEqualTo(1);
        assertThat(result.changes().available()).isFalse();
        assertThat(result.sources().available()).isFalse();
        assertThat(result.changes().message()).isEqualTo("岗位变化暂时无法读取");
        assertThat(result.reviews().available()).isTrue();
        assertThat(result.reviews().pending()).isEqualTo(2);
    }

    private static DecisionSignal decision(String title, OpportunityTier tier, LocalDate deadline) {
        return new DecisionSignal(UUID.randomUUID(), title, "杭州测试单位", "杭州", EligibilityStatus.ELIGIBLE,
            tier, 80, 75, 65, deadline);
    }
}
