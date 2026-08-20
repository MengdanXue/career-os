package com.careeros;

import com.careeros.application.AgentQueryService;
import com.careeros.application.DecisionExplanationService;
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
            .system("你是Career OS职业决策解释器。只能润色工具已经计算出的文本，不得修改资格、层级、分数、证据覆盖率，不得称为录取概率。")
            .user("用户问题："+context.question()+"\n确定性答案："+context.deterministicAnswer()+"\n结构化摘要："+context.decisions())
            .call().content();
    }

    @Bean AgentQueryService agentQueryService(DecisionRankingService rankings,DecisionExplanationService explanations,ObjectProvider<AgentQueryService.AgentPhraser> phraser) {
        return new AgentQueryService(rankings::rank,explanations,Optional.ofNullable(phraser.getIfAvailable()));
    }
}
