package com.careeros;

import com.careeros.application.ExtractionPorts.StructuredExtractor;
import com.careeros.infrastructure.extraction.NetworkntProposalValidator;
import com.careeros.infrastructure.extraction.NoModelStructuredExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExtractionModelConfiguration {
    @Bean
    NetworkntProposalValidator proposalValidator(ObjectMapper objectMapper) {
        return new NetworkntProposalValidator(objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "career-os.extraction.llm", name = "enabled", havingValue = "true")
    StructuredExtractor openAiStructuredExtractor(
        ChatClient.Builder builder,
        ObjectMapper objectMapper,
        NetworkntProposalValidator validator,
        @Value("${career-os.extraction.llm.model:gpt-5-mini}") String modelName,
        @Value("${career-os.extraction.llm.prompt-version:1.0.0}") String promptVersion,
        @Value("${career-os.extraction.llm.max-prompt-characters:40000}") int maxPromptCharacters
    ) {
        // 描述符里写的模型名必须就是实际调用的那个：此前只写进了描述符，请求用的是 Spring AI 的默认模型名，
        // 抽取记录里的"modelName"因此对不上真实调用。
        var client = builder.defaultOptions(
            org.springframework.ai.chat.prompt.ChatOptions.builder().model(modelName).build()).build();
        return new OpenAiStructuredExtractor(
            client, objectMapper, validator, modelName, promptVersion, maxPromptCharacters);
    }

    @Bean
    @ConditionalOnMissingBean(StructuredExtractor.class)
    StructuredExtractor noModelStructuredExtractor() {
        return new NoModelStructuredExtractor();
    }
}
