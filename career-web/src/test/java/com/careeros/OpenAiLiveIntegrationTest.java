package com.careeros;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.careeros.application.ExtractionPorts.ExtractionContext;
import com.careeros.domain.Evidence;
import com.careeros.domain.EvidenceFragment;
import com.careeros.domain.ParsedDocument;
import com.careeros.infrastructure.extraction.NetworkntProposalValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

@Tag("llm-integration")
class OpenAiLiveIntegrationTest {
    @Test
    void liveModelReturnsAValidatedProposal() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is not configured");
        String modelName = System.getenv().getOrDefault("CAREER_OS_AI_MODEL", "gpt-5-mini");
        OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).build();
        OpenAiChatModel model = OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model(modelName).build())
            .build();
        OpenAiStructuredExtractor extractor = new OpenAiStructuredExtractor(
            ChatClient.builder(model).build(), new ObjectMapper().findAndRegisterModules(),
            new NetworkntProposalValidator(), modelName, "prompt-v1");
        UUID evidenceId = UUID.randomUUID();
        UUID fragmentId = UUID.randomUUID();
        Instant now = Instant.now();
        Evidence evidence = new Evidence(
            evidenceId, UUID.randomUUID(), EvidenceType.OFFICIAL_NOTICE,
            "https://example.gov.cn/notice", "2026年杭州市示例单位公开招聘公告",
            null, "live-smoke", now);
        ParsedDocument parsed = new ParsedDocument(
            "live-smoke", "1.0.0", ParserQuality.ACCEPTABLE,
            List.of(new EvidenceFragment(
                fragmentId, evidenceId, LocatorType.HTML, Map.of("cssSelector", "h1"),
                "2026年杭州市示例单位公开招聘公告", "fragment-hash", now)),
            List.of());

        var result = extractor.extract(parsed, new ExtractionContext(evidence, null, null, true));

        assertThat(result.proposal().schemaVersion()).isEqualTo("1.0.0");
    }
}
