package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;

import com.careeros.application.ToolCallBudget;
import com.careeros.application.agent.AgentExecutor;
import com.careeros.application.agent.AgentTooling.Observation;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.PlanningState;
import com.careeros.application.agent.AgentTooling.ReadOnlyTool;
import com.careeros.application.agent.AgentTooling.ToolContext;
import java.util.List;
import java.util.UUID;

/**
 * 冻结样本判"模型给的这一步能不能真的执行"。
 *
 * <p>从 {@code ModelPlannerLiveSampleTest} 里抽出来，为的是它自己能被测——
 * 判据藏在一个默认不跑的类的私有方法里，判据本身写松了没有人会知道。
 */
final class FrozenSampleChecks {
    private FrozenSampleChecks() {}

    /** 下游被刻意打断。 */
    static final class SampleStop extends RuntimeException {
        SampleStop(String message) { super(message); }
    }

    static void assertUsable(String label, PlannerStep step, PlanningState state,
                             List<ReadOnlyTool> tools, AgentExecutor executor, UUID candidateId) {
        assertThat(step).as("%s：模型没有给出可解析的一步", label).isNotNull();
        if (step instanceof PlannerStep.CallTool call) {
            String name = call.call().tool();
            assertThat(executor.registeredTools())
                .as("%s：选了一个不存在的工具 %s", label, name).contains(name);
            var tool = tools.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
            Observation observation;
            try {
                observation = tool.invoke(new ToolContext(candidateId, call.call(),
                    ToolCallBudget.standard(), state.session()));
            } catch (SampleStop stopped) {
                // 只容忍我们自己埋的那个停止点：假的 job_facts 评估器故意打断下游，
                // 到这里说明参数已经过了工具的校验。
                return;
            } catch (RuntimeException blewUp) {
                // 别的异常一律是真出事了。原来这里 catch 的是 RuntimeException，
                // 于是 search_jobs 里一个 NPE 也会被当成"参数没问题"，样本照样绿。
                // 包成 AssertionError 而不是让它裸奔：报告里要看得出是哪一步、哪个工具、什么异常。
                throw new AssertionError(label + "：工具 " + name + " 抛了 "
                    + blewUp.getClass().getSimpleName() + "，这不是样本埋的停止点（"
                    + blewUp.getMessage() + "）", blewUp);
            }
            assertThat(observation.ok())
                .as("%s：参数过不了工具自己的校验——%s", label, observation.summary()).isTrue();
            return;
        }
        // 用户可见的那一步（FINISH／ASK）走执行器自己的校验，不在这里另立一套。
        //
        // 原先这里自己判两件事：模板名在不在清单里、BASIS 有没有越界。两项都过，
        // 却漏掉最要紧的一项——这条模板在这份结果下适不适用。一次查完了、一个岗位都没查到的
        // 查询撑不起"还有资料项没有确认"，线上会拒掉它，而那套自判的判据会算它通过。
        // 判据松于生产，样本量的就不是生产里会发生什么。
        assertThat(executor.validate(step, state.observations(), state.session()))
            .as("%s：这一步在生产执行器下站不住", label)
            .isEmpty();
    }
}
