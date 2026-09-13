package com.careeros;

import com.careeros.application.AgentQueryService;
import com.careeros.application.DecisionExplanationService;
import com.careeros.application.DecisionIntelligenceService;
import com.careeros.application.DecisionRankingService;
import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DecisionAgentConfiguration {
    @Bean
    @ConditionalOnProperty(prefix="career-os.agent.llm",name="enabled",havingValue="true")
    AgentQueryService.AgentPhraser decisionAgentPhraser(ChatClient.Builder builder) {
        ChatClient client=builder.build();
        return context->client.prompt()
            // 这段提示词必须与 AnswerNarrativeValidator 的规则一致。此前它写的是"润色确定性答案"，
            // 那是旧契约——模型的返回值曾经会**替换**整段答案。现在事实块由程序渲染并逐字附在后面，
            // 模型只写连接性叙述；叙述里只要出现数值或判定词就整段被拒，于是每次回答都回落。
            // 提示词与校验规则不一致时，坏的是用户看到的东西，不是测试。
            .system("""
                你是 Career OS 的叙述助手。资格、分层、分数、证据覆盖率、限制条件全部由程序渲染，
                会原样附在你这段话后面，你既改不了也删不掉。
                你只写一两句连接性叙述：说明这批岗位按什么排序、接下来建议先看什么、还缺什么信息。
                严禁写入任何数字（含中文数字），严禁写入"可报／不可报／条件可报／待确认／T1／T2／T3"
                这类判定词，严禁把指数说成录取概率、上岸率或把握。
                违反其中任意一条，你这段话会被整段丢弃。""")
            .user("用户问题："+context.question()+"\n已渲染的事实块（供你理解语境，不要复述其中的数值）："
                +context.deterministicAnswer())
            .call().content();
    }

    /**
     * 模型驱动的规划器。与叙述助手共用同一个开关。
     *
     * <p>它只负责把模型输出解析成一步计划；能不能调、调谁的、调几次由 {@code AgentExecutor} 决定。
     * 所以这里不需要"请模型不要写入"——写入工具没有注册，请求了也会被拒。
     *
     * <p>模型调用失败不抛给上层：返回空串，解析为"没有下一步"，运行干净结束。
     * 一次模型抖动不该让整个只读查询变成 500。
     */
    /** 一次模型往返的上限。超过就当这一步没给出计划，不让只读查询挂在那里。 */
    static final java.time.Duration MODEL_TURN_DEADLINE=java.time.Duration.ofSeconds(20);

    @Bean
    @ConditionalOnProperty(prefix="career-os.agent.llm",name="enabled",havingValue="true")
    com.careeros.application.agent.AgentTooling.AgentPlanner modelAgentPlanner(ChatClient.Builder builder) {
        ChatClient client=builder.build();
        // 每次往返起一个守护线程并设截止时间。客户端自身的重试与退避可能远超一次请求该等的时间：
        // 真机上模型不可达时，这个只读接口曾经 120 秒都没有返回，请求线程一直被占着。
        // 超时就返回空串，解析为"没有下一步"，运行干净结束——模型抖动不该让查询变成挂起。
        var pool=java.util.concurrent.Executors.newCachedThreadPool(runnable->{
            var thread=new Thread(runnable,"agent-model-turn");
            thread.setDaemon(true);
            return thread;
        });
        return new com.careeros.application.agent.ModelPlanner((protocol,state)->
            boundedTurn(pool, MODEL_TURN_DEADLINE,
                ()->client.prompt().system(protocol).user(state).call().content()));
    }

    /**
     * 带截止时间地跑一次模型往返。
     *
     * <p>超时、失败、被中断一律返回空串——解析成"没有下一步"，运行干净结束。
     * 返回 null 或抛出去都会让调用方多一条要处理的路径，而这三种情况对规划来说是同一件事：
     * 这一步没有计划。
     */
    static String boundedTurn(java.util.concurrent.ExecutorService pool, java.time.Duration deadline,
                              java.util.concurrent.Callable<String> call) {
        var future=pool.submit(call);
        try {
            String answer=future.get(deadline.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return answer == null ? "" : answer;
        } catch (java.util.concurrent.TimeoutException timeout) {
            future.cancel(true);
            return "";
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return "";
        } catch (java.util.concurrent.ExecutionException failure) {
            return "";
        }
    }

    @Bean AgentQueryService agentQueryService(DecisionRankingService rankings,DecisionExplanationService explanations,ObjectProvider<AgentQueryService.AgentPhraser> phraser,DecisionIntelligenceService decisions) {
        return new AgentQueryService(rankings::rank,explanations,
            Optional.ofNullable(phraser.getIfAvailable()),Optional.of(decisions::assess));
    }
}
