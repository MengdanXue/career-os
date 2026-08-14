package com.careeros.agent.web;

import com.careeros.agent.service.CareerOsAgentService;
import com.careeros.agent.tool.CareerOsTools;
import com.careeros.agent.workflow.CareerWorkflowEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class CareerOsAgentController {
    private final CareerOsAgentService agent;
    private final CareerOsTools tools;
    private final CareerWorkflowEngine workflow;

    public CareerOsAgentController(CareerOsAgentService agent, CareerOsTools tools, CareerWorkflowEngine workflow) {
        this.agent = agent;
        this.tools = tools;
        this.workflow = workflow;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "model_available", agent.modelAvailable(), "tools", tools.names());
    }

    @PostMapping("/run")
    public CareerOsAgentService.AgentResponse run(@RequestBody AgentRequest request) throws Exception {
        String message = request.message() == null || request.message().isBlank()
                ? "执行今天的官方招聘增量采集并汇报 IT 候选"
                : request.message();
        return agent.run(message);
    }

    @PostMapping("/daily")
    public CareerWorkflowEngine.DailyRunReport daily() throws Exception {
        return workflow.runDaily();
    }

    public record AgentRequest(String message) {}
}
