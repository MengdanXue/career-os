package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 配置的模型名必须真的出现在发给模型的请求里。
 *
 * <p>此前规划器和抽取器都用 {@code builder.build()}：模型名从没传下去，请求里是 Spring AI 自带的默认名。
 * 接 OpenAI 兼容端点（DeepSeek、通义）时端点不认这个名字，第一步就失败——而这条路径在真实凭据下
 * 从没跑过，所以没人发现。
 */
class ModelClientConfigurationTest {

    private static ChatModel capturing(AtomicReference<Prompt> sink) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                sink.set(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("FINISH SINGLE_JOB\nBASIS 1"))));
            }
        };
    }

    @Test void thePlannerSendsTheConfiguredModelName() {
        var sent = new AtomicReference<Prompt>();
        var client = DecisionAgentConfiguration.modelClient(ChatClient.builder(capturing(sent)), "deepseek-chat", 0.0);

        client.prompt().system("协议").user("状态").call().content();

        assertThat(sent.get().getOptions().getModel()).isEqualTo("deepseek-chat");
        assertThat(sent.get().getOptions().getTemperature()).isEqualTo(0.0);
    }

    /** 没配温度就不发：部分推理模型只接受默认温度，硬发 0 会被端点拒绝。 */
    @Test void anUnsetTemperatureIsNotSent() {
        var sent = new AtomicReference<Prompt>();
        var client = DecisionAgentConfiguration.modelClient(ChatClient.builder(capturing(sent)), "qwen-plus", null);

        client.prompt().user("状态").call().content();

        assertThat(sent.get().getOptions().getModel()).isEqualTo("qwen-plus");
        assertThat(sent.get().getOptions().getTemperature()).isNull();
    }

    @Test void aBlankModelNameFailsAtStartupInsteadOfCallingTheDefault() {
        assertThatThrownBy(() -> DecisionAgentConfiguration.modelClient(ChatClient.builder(capturing(new AtomicReference<>())), " ", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("career-os.agent.llm.model");
    }

    /** 台账里的规划器身份：模型名 + 端点主机，不带 key、不带路径。 */
    @Test void thePlannerIdentityNamesTheModelAndTheEndpointHost() {
        assertThat(DecisionAgentConfiguration.modelIdentity("deepseek-chat", "https://api.deepseek.com"))
            .isEqualTo("model:deepseek-chat@api.deepseek.com");
        assertThat(DecisionAgentConfiguration.modelIdentity("qwen-plus", "https://dashscope.aliyuncs.com/compatible-mode"))
            .isEqualTo("model:qwen-plus@dashscope.aliyuncs.com");
        assertThat(DecisionAgentConfiguration.modelIdentity("m", "not a url"))
            .isEqualTo("model:m@unknown-endpoint");
    }

    /** 抽取器同理：描述符里写的模型名必须就是请求里的那个。 */
    @Test void theExtractorAlsoSendsItsConfiguredModelName() {
        var builder = mock(ChatClient.Builder.class, RETURNS_SELF);

        new ExtractionModelConfiguration().openAiStructuredExtractor(builder, new ObjectMapper(),
            new com.careeros.infrastructure.extraction.NetworkntProposalValidator(new ObjectMapper()),
            "qwen-plus", "1.0.0", 40_000);

        verify(builder).defaultOptions(argThat(options -> "qwen-plus".equals(options.getModel())));
    }
}
