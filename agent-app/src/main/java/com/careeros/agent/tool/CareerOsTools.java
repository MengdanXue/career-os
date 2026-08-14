package com.careeros.agent.tool;

import com.careeros.agent.domain.EligibilityAssessment;
import com.careeros.agent.domain.IncrementalWatchlist;
import com.careeros.agent.domain.OpportunityTierAssessment;
import com.careeros.agent.workflow.CareerWorkflowEngine;
import com.careeros.agent.workflow.CareerWorkflowEngine.DeltaResult;
import com.careeros.agent.workflow.CareerWorkflowEngine.ImportResult;
import com.careeros.agent.workflow.CareerWorkflowEngine.MatchResult;
import com.careeros.agent.workflow.CareerWorkflowEngine.ScanResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CareerOsTools {
    private final CareerWorkflowEngine workflow;

    public CareerOsTools(CareerWorkflowEngine workflow) {
        this.workflow = workflow;
    }

    @Tool(name = "scan_official_recruitment_sources", description = "扫描杭州国企官方招聘入口，识别公告、附件和明确空状态。每天工作流必须先调用此工具。")
    public ScanResult scanOfficialRecruitmentSources() {
        return execute(workflow::scanSources);
    }

    @Tool(name = "import_latest_job_attachment", description = "读取最新扫描结果，下载最新官方招聘 Excel，并结构化为通过 Schema 校验的岗位。必须在扫描工具之后调用。")
    public ImportResult importLatestJobAttachment() {
        return execute(workflow::importLatestAttachment);
    }

    @Tool(name = "calculate_incremental_job_changes", description = "比较当前岗位与持久化状态，返回新增、内容变更、下线和未变化数量。必须在附件导入后调用。")
    public DeltaResult calculateIncrementalJobChanges() {
        return execute(workflow::calculateChanges);
    }

    @Tool(name = "find_it_job_candidates", description = "读取结构化岗位并返回符合信息技术方向的候选岗位及可解释评分。必须在附件导入后调用。")
    public MatchResult findItJobCandidates() {
        return execute(workflow::findItCandidates);
    }

    @Tool(name = "evaluate_job_eligibility", description = "针对指定岗位执行逐条硬资格判断，返回可报、不可报、条件满足或待确认。不得用 IT 关键词分数代替此结果。")
    public EligibilityAssessment evaluateJobEligibility(String jobId) {
        return execute(() -> workflow.evaluateJobEligibility(jobId));
    }

    @Tool(name = "classify_job_opportunity_tier", description = "将岗位分入 T1 明确事业编主攻、T2 用工身份待核、T3 稳定国企备选、排除或未知。国企不得被标成事业编。")
    public OpportunityTierAssessment classifyJobOpportunityTier(String jobId) {
        return execute(() -> workflow.classifyJobOpportunityTier(jobId));
    }

    @Tool(name = "generate_incremental_watchlist", description = "将本次新增、变更和下线岗位与候选人资格及 T1/T2/T3 分层结合，生成只包含变化的行动清单。必须在增量计算后调用。")
    public IncrementalWatchlist generateIncrementalWatchlist() {
        return execute(workflow::generateIncrementalWatchlist);
    }

    public List<String> names() {
        return List.of(
                "scan_official_recruitment_sources",
                "import_latest_job_attachment",
                "calculate_incremental_job_changes",
                "find_it_job_candidates",
                "evaluate_job_eligibility",
                "classify_job_opportunity_tier",
                "generate_incremental_watchlist"
        );
    }

    private static <T> T execute(CheckedSupplier<T> action) {
        try {
            return action.get();
        } catch (Exception error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
