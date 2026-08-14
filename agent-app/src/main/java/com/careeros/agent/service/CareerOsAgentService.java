package com.careeros.agent.service;

import com.careeros.agent.tool.CareerOsTools;
import com.careeros.agent.workflow.CareerWorkflowEngine;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class CareerOsAgentService {
    private static final String SYSTEM_PROMPT = """
            你是 Career OS 职业决策 Agent，面向杭州、浙江的事业编技术岗、稳定科研/高校技术岗和国企数字平台岗。
            你的目标是从官方来源发现岗位变化，区分用工身份，执行带证据的资格判断，并给出可追溯结果。
            执行每日采集任务时必须依次使用：扫描官方来源、导入最新附件、计算增量变化、筛选 IT 候选、生成增量关注清单。
            当用户询问某个岗位是否可报时，必须调用资格判断工具，并逐条说明硬条件。
            当用户询问岗位是否属于半体制、是否有编制或是否值得进入目标池时，必须调用机会分层工具。
            IT 相关度只表示文本分类结果，不是资格、稳定性、上岸概率或最终推荐分。
            明确区分事业编目标与国企备选；没有公告证据时不得声称岗位有编制。
            缺少出生日期、留服专业名、认证时间、政治面貌、应届或社保信息时，输出待确认或条件式结论，不得猜测。
            只根据工具结果回答，不得虚构岗位。明确区分 content_found、empty_confirmed 和失败。
            报告必须优先说明新增、变更和下线岗位；未变化岗位只报告数量。
            """;

    private final ObjectProvider<ChatModel> chatModel;
    private final CareerOsTools tools;
    private final CareerWorkflowEngine workflow;

    public CareerOsAgentService(
            ObjectProvider<ChatModel> chatModel,
            CareerOsTools tools,
            CareerWorkflowEngine workflow
    ) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.workflow = workflow;
    }

    public AgentResponse run(String userRequest) throws Exception {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            return new AgentResponse("deterministic_fallback", workflow.runDaily());
        }
        String content = ChatClient.builder(model).build()
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(userRequest)
                .tools(tools)
                .call()
                .content();
        return new AgentResponse("llm_tool_calling", content);
    }

    public boolean modelAvailable() {
        return chatModel.getIfAvailable() != null;
    }

    public record AgentResponse(String mode, Object output) {}
}
