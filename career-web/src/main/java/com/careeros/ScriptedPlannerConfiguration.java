package com.careeros;

import com.careeros.application.agent.AgentTooling.AgentPlanner;
import com.careeros.application.agent.AgentTooling.Basis;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.ToolCall;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 验收专用的可控规划器，默认不启用。
 *
 * <p>存在的唯一理由是：在没有模型 key 的环境里，把执行边界和工具面在**真实数据**上跑一遍。
 * 它按工具结果分支（有岗位就看待确认事项，没岗位就转去追问），所以能验证执行器的行为；
 * 但它**不是模型，也不是 Agent**——它的分支是写死的，换个问题不会换策略。
 *
 * <p>用 {@code career-os.agent.planner=scripted} 显式打开。不要在生产启用：
 * 那会让这个接口后面看起来有个会自己选工具的东西，而实际上没有。
 */
@Configuration
@ConditionalOnProperty(name = "career-os.agent.planner", havingValue = "scripted")
class ScriptedPlannerConfiguration {

    @Bean
    AgentPlanner scriptedAcceptancePlanner(Environment environment) {
        AcceptancePlanners.refuseWhenTheRealModelIsEnabled(environment, "scripted");
        return state -> {
            var last = state.last();
            int seen = state.observations().size();
            if (last == null) {
                return new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先看这个范围里有没有岗位");
            }
            if (last.tool().equals("search_jobs")) {
                // 读不到就只能问用户，这条追问不依据任何结果，如实标成纯澄清。
                if (!last.ok()) return new PlannerStep.AskUser("RETRY_LATER", Basis.clarifying());
                int count = last.value("count", 0);
                return count == 0
                    ? new PlannerStep.AskUser("BROADEN_SCOPE", Basis.on(seen))
                    : new PlannerStep.CallTool(ToolCall.of("pending_confirmations"), "有岗位，看看还缺什么确认");
            }
            if (last.tool().equals("pending_confirmations")) {
                int count = last.value("count", 0);
                return count > 0
                    ? new PlannerStep.CallTool(ToolCall.of("watchlist"), "还有待确认项，顺带看看关注清单")
                    : new PlannerStep.Finish("RANKED_LISTING", Basis.on(seen - 1, seen));
            }
            return new PlannerStep.Finish("PENDING_FIRST", Basis.on(seen - 1, seen));
        };
    }
}
