package com.careeros;

import org.springframework.core.env.Environment;

/**
 * 验收用规划器的共同守卫：不能和真实模型同时启用。
 *
 * <p>真实模型那个 bean 挂在 {@code career-os.agent.llm.enabled=true} 上，
 * 验收用的两个挂在 {@code career-os.agent.planner=recorded|scripted} 上——
 * 两个互不相干的属性，可以同时为真。同时为真时容器里就有两个 AgentPlanner，
 * 而运行接口取 bean 用的是 {@code ObjectProvider.getIfAvailable()}，多候选时它抛异常：
 * <b>每一次运行都 500</b>，而且报错指向 Spring 内部，看不出是配置写重了。
 *
 * <p>所以在这里当场拦住，并把该关哪个说清楚。启动失败比运行时 500 好：
 * 前者一定会被发现，后者要等到有人真的问了一句话才暴露，而那时页面上只有一个 500。
 *
 * <p>为什么不让模型那个 bean 变成 {@code @Primary}：那会在两个都开着时<b>静默</b>选模型，
 * 于是"我明明配了回放"和"它怎么在调模型"这两件事同时成立，还没有任何提示。
 */
final class AcceptancePlanners {
    private AcceptancePlanners() {}

    /** 真实模型开着时，验收规划器一律拒绝启动。 */
    static void refuseWhenTheRealModelIsEnabled(Environment environment, String plannerValue) {
        // 只认明确的 "true"。取值写成别的一律当没开——配置项做花式解析，
        // 就会出现"我写了 yes 你怎么没开"这种没法排查的事。
        if (!Boolean.parseBoolean(environment.getProperty("career-os.agent.llm.enabled"))) return;
        throw new IllegalStateException(
            "career-os.agent.planner=" + plannerValue + " 与 career-os.agent.llm.enabled=true 同时开着。"
                + "两者各自注册一个 AgentPlanner，容器里就有两个，运行接口取 bean 时会失败。"
                + "只能留一个：跑验收就去掉 career-os.agent.llm.enabled，"
                + "接真实模型就去掉 career-os.agent.planner。");
    }
}
