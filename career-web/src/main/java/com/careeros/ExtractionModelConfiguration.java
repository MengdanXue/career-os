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
        @Value("${career-os.extraction.llm.prompt-version:1.0.0}") String promptVersion
    ) {
        return new OpenAiStructuredExtractor(builder.build(), objectMapper, validator, modelName, promptVersion);
    }

    @Bean
    @ConditionalOnMissingBean(StructuredExtractor.class)
    StructuredExtractor noModelStructuredExtractor() {
        return new NoModelStructuredExtractor();
    }
}
