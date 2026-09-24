package com.careeros.application.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.agent.AgentRunLedger.EvidenceRef;
import com.careeros.application.agent.AgentRunLedger.RunRecord;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.agent.AgentTooling.ToolContext;
import com.careeros.application.agent.AgentTooling.ToolParameter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 台账要能回答三件事：这一步是谁选的（模型还是回放）、调了什么、凭哪段官方原文。
 */
class AgentRunLedgerTest {
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final String JOB = "01992f09-0000-7000-8000-0000000000aa";
    private static final String FRAGMENT = "01992f09-0000-7000-8000-0000000000ff";
    private static final Instant STARTED = Instant.parse("2026-09-24T08:00:00Z");

    private static ReadOnlyTool tool(String name, List<ToolParameter> parameters, Map<String, Object> data) {
        return new ReadOnlyTool() {
            public String name() { return name; }
            public String description() { return name; }
            public List<ToolParameter> parameters() { return parameters; }
            public Observation invoke(ToolContext context) { return Observation.ok(name, name + " ok", data); }
        };
    }

    private static AgentExecutor executor() {
        var search = tool("search_jobs", List.of(ToolParameter.optional("location", "城市")), Map.of(
            "count", 1, "complete", true, "notAssessed", 0,
            "jobs", List.of(Map.of("jobPostingId", JOB, "jobTitle", "信息中心工作人员",
                "officialSourceUrl", "https://hrss.hangzhou.gov.cn/art/2026/notice.html"))));
        var facts = tool("job_facts", List.of(ToolParameter.optional("jobId", "岗位")), Map.of(
            "jobPostingId", JOB, "jobTitle", "信息中心工作人员",
            "officialSourceUrl", "https://hrss.hangzhou.gov.cn/art/2026/notice.html",
            "evidence", List.of(Map.of("field", "otherRequirements", "sourceUrl",
                "https://hrss.hangzhou.gov.cn/jobs.xlsx", "locator", "Sheet1!H12", "excerpt", "中共党员",
                "evidenceFragmentId", FRAGMENT)),
            "rules", List.of(Map.of("rule", "POLITICAL_AFFILIATION", "status", "NEEDS_CONFIRMATION",
                "explanation", "候选人政治面貌尚未确认", "evidenceFragmentIds", List.of(FRAGMENT)))));
        return new AgentExecutor(List.of(search, facts));
    }

    /** 模型原样返回的三轮输出：查、看详情、收尾。第一轮还带一个不存在的工具，会被拒。 */
    private static ModelPlanner planner(List<ModelPlanner.RecordedTurn> sink) {
        var outputs = new ArrayDeque<>(List.of(
            "TOOL update_profile\nWHY 想改资料",
            "TOOL search_jobs location=杭州\nWHY 先看有没有岗位",
            "TOOL job_facts jobId=" + JOB + "\nWHY 看第一个的限制条件",
            "FINISH SINGLE_JOB\nBASIS 3"));
        return new ModelPlanner((protocol, state) -> outputs.poll(), "model:deepseek-chat@api.deepseek.com")
            .recording(sink::add);
    }

    @Test void everyStepEveryModelTurnAndTheEvidenceBehindThemAreRecorded() {
        var turns = new ArrayList<ModelPlanner.RecordedTurn>();
        var planner = planner(turns);
        var run = executor().run(CANDIDATE, "杭州的信息中心岗怎么样", planner);

        var record = RunRecord.of(UUID.randomUUID(), CANDIDATE, null, "杭州的信息中心岗怎么样",
            planner.identity(), run, STARTED, STARTED.plusSeconds(3), turns);

        assertThat(record.plannerIdentity()).isEqualTo("model:deepseek-chat@api.deepseek.com");
        assertThat(record.outcome()).isEqualTo("FINISHED");
        assertThat(record.narrative()).isNotBlank();

        // 被拒的那一步也在台账里：看不见的拦截等于没拦截。
        assertThat(record.steps()).extracting(AgentRunLedger.StepRecord::tool)
            .containsExactly("update_profile", "search_jobs", "job_facts");
        assertThat(record.steps().getFirst().accepted()).isFalse();
        assertThat(record.steps().getFirst().evidence()).isEmpty();
        assertThat(record.steps().get(1).arguments()).containsEntry("location", "杭州");
        assertThat(record.steps().get(1).why()).isEqualTo("先看有没有岗位");

        // 证据：公告链接、字段原文、规则引用的片段，而且规则那一条能追到同一段原文。
        assertThat(record.steps().get(1).evidence()).containsExactly(new EvidenceRef("OFFICIAL_SOURCE", JOB, null,
            "https://hrss.hangzhou.gov.cn/art/2026/notice.html", null, null, null));
        assertThat(record.steps().get(2).evidence()).extracting(EvidenceRef::kind)
            .containsExactly("OFFICIAL_SOURCE", "FIELD", "RULE");
        assertThat(record.steps().get(2).evidence().get(2)).satisfies(rule -> {
            assertThat(rule.field()).isEqualTo("POLITICAL_AFFILIATION");
            assertThat(rule.evidenceFragmentId()).isEqualTo(FRAGMENT);
            assertThat(rule.sourceUrl()).isEqualTo("https://hrss.hangzhou.gov.cn/jobs.xlsx");
            assertThat(rule.excerpt()).isEqualTo("中共党员");
        });
        assertThat(record.evidence()).hasSize(3);

        // 模型每一轮的原文都在，可以据此回放；提示只存哈希。
        assertThat(record.modelTurns()).hasSize(4);
        assertThat(record.modelTurns().getLast().response()).isEqualTo("FINISH SINGLE_JOB\nBASIS 3");
        assertThat(record.modelTurns().getFirst().promptSha256()).hasSize(64);
        assertThat(record.modelTurns().getFirst().state()).contains("杭州的信息中心岗怎么样");
    }

    /** 录制按运行分开：共享的规划器单例不能把两次运行的往返混进同一份记录。 */
    @Test void recordingIsPerRunAndLeavesTheSharedPlannerUntouched() {
        var base = new ModelPlanner((protocol, state) -> "FINISH SINGLE_JOB\nBASIS 1", "recorded-replay");
        var first = new ArrayList<ModelPlanner.RecordedTurn>();
        var second = new ArrayList<ModelPlanner.RecordedTurn>();

        executor().run(CANDIDATE, "第一问", base.recording(first::add));
        executor().run(CANDIDATE, "第二问", base.recording(second::add));
        executor().run(CANDIDATE, "第三问", base);

        assertThat(first).singleElement().extracting(ModelPlanner.RecordedTurn::state).asString().contains("第一问");
        assertThat(second).singleElement().extracting(ModelPlanner.RecordedTurn::state).asString().contains("第二问");
        assertThat(base.recording(turn -> {}).identity()).isEqualTo("recorded-replay");
    }

    @Test void aPlannerWithoutItsOwnNameCanBeLabelled() {
        AgentTooling.AgentPlanner lambda = state -> null;
        assertThat(lambda.identity()).isEqualTo("unlabelled");
        assertThat(AgentTooling.labelled("scripted", lambda).identity()).isEqualTo("scripted");
    }
}
