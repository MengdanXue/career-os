package com.careeros;

import com.careeros.application.agent.AgentTooling.AgentPlanner;
import com.careeros.application.agent.AgentTooling.PlannerStep;
import com.careeros.application.agent.AgentTooling.ToolCall;
import com.careeros.application.agent.ModelPlanner;
import java.util.ArrayDeque;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    AgentPlanner scriptedAcceptancePlanner() {
        return state -> {
            var last = state.last();
            if (last == null) {
                return new PlannerStep.CallTool(ToolCall.of("search_jobs"), "先看这个范围里有没有岗位");
            }
            if (last.tool().equals("search_jobs")) {
                if (!last.ok()) return new PlannerStep.AskUser("岗位库这次读不到，要不要稍后再试？");
                int count = last.value("count", 0);
                return count == 0
                    ? new PlannerStep.AskUser("这个范围内没有岗位，要不要放宽城市或职位类别？")
                    : new PlannerStep.CallTool(ToolCall.of("pending_confirmations"), "有岗位，看看还缺什么确认");
            }
            if (last.tool().equals("pending_confirmations")) {
                int count = last.value("count", 0);
                return count > 0
                    ? new PlannerStep.CallTool(ToolCall.of("watchlist"), "还有待确认项，顺带看看关注清单")
                    : new PlannerStep.Finish("下面按稳定性排序，资料暂时没有需要你补充的地方。");
            }
            return new PlannerStep.Finish("下面按稳定性排序，其中一处仍需你补充材料后才能定。");
        };
    }
}

/**
 * 回放录制输出的规划器，默认不启用。
 *
 * <p>用途只有一个：在没有模型 key 的环境里，把<b>模型输出那条路径</b>在真实数据上跑通——
 * 提示是怎么拼的、格式错误怎么处理、非法参数怎么被拒、预算怎么扣。
 * {@link ScriptedPlannerConfiguration} 里那个规划器绕过了解析层，测不到这些。
 *
 * <p>它<b>不是模型</b>：输出是事先写死的，换个问题回的还是同一串。
 * 它证明的是"这条路径接通了"，不是"模型会选工具"。后者要等真实凭据下的对照样本。
 *
 * <p>用 {@code career-os.agent.planner=recorded} 打开，
 * 用 {@code career-os.agent.recorded-turns[i]} 给出每一轮的原样输出。
 */
@Configuration
@ConditionalOnProperty(name = "career-os.agent.planner", havingValue = "recorded")
class RecordedPlannerConfiguration {

    @Bean
    AgentPlanner recordedAcceptancePlanner(org.springframework.core.env.Environment environment) {
        // 用 Binder 而不是 @Value：@Value 对 List 是按逗号切一个字符串，
        // YAML 里的多行列表根本绑不上，结果是"配了却没生效"——静默空队列，看起来像模型不回话。
        var turns = org.springframework.boot.context.properties.bind.Binder.get(environment)
            .bind("career-os.agent.recorded-turns",
                org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
            .orElseGet(List::of);
        var queue = new ArrayDeque<>(turns);
        var log = org.slf4j.LoggerFactory.getLogger(RecordedPlannerConfiguration.class);
        log.info("[recorded-planner] 已录制 {} 轮输出", turns.size());
        return new ModelPlanner((protocol, state) -> {
            // 提示整段打出来：要能在真实进程里核对模型首轮收到的是什么，而不是只看代码。
            // 只有这个验收专用的 bean 会这么做，模型真正接上时走的是另一条实现。
            log.info("[recorded-planner] 发给模型的提示：\n{}\n---- 状态 ----\n{}", protocol, state);
            String next = queue.isEmpty() ? "" : queue.poll();
            log.info("[recorded-planner] 本轮回放：<<<{}>>>", next);
            return next;
        });
    }
}
