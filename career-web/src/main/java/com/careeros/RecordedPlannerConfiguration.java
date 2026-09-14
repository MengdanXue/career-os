package com.careeros;

import com.careeros.application.agent.AgentTooling.AgentPlanner;
import com.careeros.application.agent.ModelPlanner;
import java.util.ArrayDeque;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
