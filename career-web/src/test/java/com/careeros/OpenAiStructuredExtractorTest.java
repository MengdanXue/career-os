package com.careeros;

import static com.careeros.domain.DomainEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.ExtractionExceptions;
import com.careeros.application.ExtractionPorts.ExtractionContext;
import com.careeros.domain.Evidence;
import com.careeros.domain.EvidenceFragment;
import com.careeros.domain.ParsedDocument;
import com.careeros.infrastructure.extraction.NetworkntProposalValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

class OpenAiStructuredExtractorTest {
    private static final UUID EVIDENCE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID FRAGMENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Test
    void validResponseNeedsOneModelCall() {
        QueueGateway gateway = new QueueGateway(validJson());
        OpenAiStructuredExtractor extractor = extractor(gateway);

        var result = extractor.extract(parsed(), context());

        assertThat(result.proposal().schemaVersion()).isEqualTo("1.0.0");
        assertThat(gateway.calls).isEqualTo(1);
    }

    @Test
    void malformedFirstResponseCanBeRepairedOnce() {
        QueueGateway gateway = new QueueGateway("```json\n{bad}\n```", validJson());
        OpenAiStructuredExtractor extractor = extractor(gateway);

        var result = extractor.extract(parsed(), context());

        assertThat(result.proposal().jobs()).isEmpty();
        assertThat(gateway.calls).isEqualTo(2);
        assertThat(gateway.lastUserPrompt).contains("repair");
    }

    @Test
    void twoMalformedResponsesAreRejectedWithoutThirdCall() {
        QueueGateway gateway = new QueueGateway("{bad}", "{still-bad}");
        OpenAiStructuredExtractor extractor = extractor(gateway);

        assertThatThrownBy(() -> extractor.extract(parsed(), context()))
            .isInstanceOf(ExtractionExceptions.InvalidProposalException.class);
        assertThat(gateway.calls).isEqualTo(2);
    }

    @Test
    void springAiHttpClientUsesOneOpenAiRequestForValidOutput() throws Exception {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        try {
            server.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson(openAiResponse(validJson()))));
            OpenAiStructuredExtractor extractor = springAiExtractor(server);

            extractor.extract(parsed(), context());

            server.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        } finally {
            server.stop();
        }
    }

    @Test
    void springAiHttpClientUsesExactlyTwoRequestsForOneRepair() throws Exception {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        try {
            server.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .inScenario("repair")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(okJson(openAiResponse("{bad}")))
                .willSetStateTo("second"));
            server.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .inScenario("repair")
                .whenScenarioStateIs("second")
                .willReturn(okJson(openAiResponse(validJson()))));
            OpenAiStructuredExtractor extractor = springAiExtractor(server);

            extractor.extract(parsed(), context());

            server.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        } finally {
            server.stop();
        }
    }

    private static OpenAiStructuredExtractor extractor(QueueGateway gateway) {
        return new OpenAiStructuredExtractor(
            gateway, new ObjectMapper().findAndRegisterModules(),
            new NetworkntProposalValidator(), "fixture-model", "prompt-v1");
    }

    private static OpenAiStructuredExtractor extractor(QueueGateway gateway, int maxPromptCharacters) {
        return new OpenAiStructuredExtractor(
            gateway, new ObjectMapper().findAndRegisterModules(),
            new NetworkntProposalValidator(), "fixture-model", "prompt-v1", maxPromptCharacters);
    }

    private static ParsedDocument parsedWithFragments(int count, int charactersEach) {
        List<EvidenceFragment> fragments = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            fragments.add(new EvidenceFragment(
                UUID.nameUUIDFromBytes(("fragment-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                EVIDENCE_ID, LocatorType.HTML, Map.of("cssSelector", "p:nth-child(" + index + ")"),
                // 每条正文必须唯一，否则"这条是否被送进 prompt"根本无法区分
                "岗位" + index + "说明".repeat(Math.max(1, charactersEach / 4)), "hash-" + index,
                Instant.parse("2026-08-14T10:00:00Z")));
        }
        return new ParsedDocument("jsoup", "1.22.2", ParserQuality.ACCEPTABLE, fragments, List.of());
    }

    private static OpenAiStructuredExtractor springAiExtractor(WireMockServer server) {
        OpenAiApi api = OpenAiApi.builder()
            .baseUrl(server.baseUrl())
            .apiKey("test-key")
            .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model("fixture-model").build())
            .build();
        return new OpenAiStructuredExtractor(
            ChatClient.builder(model).build(), new ObjectMapper().findAndRegisterModules(),
            new NetworkntProposalValidator(), "fixture-model", "prompt-v1");
    }

    private static String openAiResponse(String content) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
            "id", "chatcmpl-fixture",
            "object", "chat.completion",
            "created", 1786744800,
            "model", "fixture-model",
            "choices", List.of(Map.of(
                "index", 0,
                "message", Map.of("role", "assistant", "content", content),
                "finish_reason", "stop")),
            "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30)));
    }

    private static ParsedDocument parsed() {
        return new ParsedDocument(
            "jsoup", "1.22.2", ParserQuality.ACCEPTABLE,
            List.of(new EvidenceFragment(
                FRAGMENT_ID, EVIDENCE_ID, LocatorType.HTML, Map.of("cssSelector", "h1"),
                "2026年公开招聘公告", "fragment-hash", Instant.parse("2026-08-14T10:00:00Z"))),
            List.of());
    }

    private static ExtractionContext context() {
        Evidence evidence = new Evidence(
            EVIDENCE_ID, UUID.randomUUID(), EvidenceType.OFFICIAL_NOTICE,
            "https://example.gov.cn/notice/1", "2026年公开招聘公告", null,
            "artifact-hash", Instant.parse("2026-08-14T10:00:00Z"));
        return new ExtractionContext(evidence, null, null, false);
    }

    @Test
    void oversizedDocumentIsTruncatedToTheBudgetAndFlaggedForReview() {
        QueueGateway gateway = new QueueGateway(validJson());
        // 每条片段约 400 字符，共 200 条 ≈ 80k 字符，预算 5000 只装得下一小部分
        var result = extractor(gateway, 5_000).extract(parsedWithFragments(200, 400), context());

        assertThat(gateway.lastUserPrompt.length()).isLessThan(6_000);
        assertThat(gateway.lastUserPrompt).contains("this document was truncated");
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst())
            .contains("extraction budget")
            .contains("were dropped");
    }

    @Test
    void fragmentsAreDroppedWholeSoCitedEvidenceStaysVerbatim() {
        QueueGateway gateway = new QueueGateway(validJson());
        ParsedDocument document = parsedWithFragments(50, 400);

        extractor(gateway, 5_000).extract(document, context());

        // 送进去的每条片段都必须是完整原文，否则模型引用的 fragmentId
        // 会对应半句话，证据校验就会拿残缺文本去比对。
        long present = document.fragments().stream()
            .filter(fragment -> gateway.lastUserPrompt.contains(fragment.verbatimText()))
            .count();
        long mentioned = document.fragments().stream()
            .filter(fragment -> gateway.lastUserPrompt.contains(fragment.id().toString()))
            .count();
        assertThat(present).isEqualTo(mentioned).isPositive().isLessThan(document.fragments().size());
    }

    @Test
    void documentWithinBudgetCarriesNoWarningAndNoTruncationNotice() {
        QueueGateway gateway = new QueueGateway(validJson());

        var result = extractor(gateway, 40_000).extract(parsed(), context());

        assertThat(result.warnings()).isEmpty();
        assertThat(gateway.lastUserPrompt).doesNotContain("truncated");
    }

    @Test
    void repairPromptDoesNotEchoAnUnboundedInvalidResponse() {
        String hugeInvalid = "x".repeat(50_000);
        QueueGateway gateway = new QueueGateway(hugeInvalid, validJson());

        extractor(gateway).extract(parsed(), context());

        assertThat(gateway.calls).isEqualTo(2);
        assertThat(gateway.lastUserPrompt).contains("[truncated");
        assertThat(gateway.lastUserPrompt.length()).isLessThan(10_000);
    }

    private static String validJson() {
        return """
            {
              "schemaVersion":"1.0.0",
              "source":{"evidenceId":"30000000-0000-0000-0000-000000000001","sourceUrl":"https://example.gov.cn/notice/1","sourceTitle":"2026年公开招聘公告"},
              "organization":{"name":"待核验单位","organizationType":{"value":null,"factStatus":"UNKNOWN","confidence":0.0,"evidenceFragmentIds":[],"interpretation":null}},
              "recruitmentEvent":{"title":"2026年公开招聘公告","recruitmentYear":2026,"eventType":"OTHER","publishedOn":{"value":null,"factStatus":"UNKNOWN","confidence":0.0,"evidenceFragmentIds":[],"interpretation":null},"applicationStartsOn":{"value":null,"factStatus":"UNKNOWN","confidence":0.0,"evidenceFragmentIds":[],"interpretation":null},"applicationEndsOn":{"value":null,"factStatus":"UNKNOWN","confidence":0.0,"evidenceFragmentIds":[],"interpretation":null}},
              "jobs":[],
              "warnings":["MODEL_FIXTURE"],
              "confidence":0.5,
              "completeSnapshot":false
            }
            """;
    }

    private static final class QueueGateway implements OpenAiStructuredExtractor.RawChatGateway {
        private final ArrayDeque<String> responses = new ArrayDeque<>();
        private int calls;
        private String lastUserPrompt;

        QueueGateway(String... responses) { this.responses.addAll(List.of(responses)); }

        @Override public String complete(String systemPrompt, String userPrompt) {
            calls++;
            lastUserPrompt = userPrompt;
            return responses.removeFirst();
        }
    }
}
