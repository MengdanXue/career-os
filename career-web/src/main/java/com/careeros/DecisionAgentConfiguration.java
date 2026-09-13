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

    @Bean AgentQueryService agentQueryService(DecisionRankingService rankings,DecisionExplanationService explanations,ObjectProvider<AgentQueryService.AgentPhraser> phraser,DecisionIntelligenceService decisions) {
        return new AgentQueryService(rankings::rank,explanations,
            Optional.ofNullable(phraser.getIfAvailable()),Optional.of(decisions::assess));
    }
}
