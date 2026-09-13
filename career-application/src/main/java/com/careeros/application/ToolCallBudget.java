package com.careeros.application;

/**
 * 一次请求里允许向下游发起多少次调用。
 *
 * <p>关注清单和资料变更比对的扇出都由用户数据决定：关注了 500 个岗位，一次列表请求就会触发
 * 500 次评估。没有上限时，这不是"慢一点"，而是一个用户能自己把服务打垮的入口。
 *
 * <p><b>耗尽必须可见。</b> 这是本类唯一重要的约定。悄悄截断会产出一份看起来完整、实际不完整的
 * 结果——清单少列几个还只是遗漏，差异比对少算几个则会少报"新增不可报"，读起来就是"没出什么事"。
 * 所以调用方要么把"还有多少没刷新"一起报出来（清单），要么直接拒绝给出结论（比对）。
 * 两者都不允许假装算完了。
 *
 * <p>不是线程安全的：一次请求一个预算，本来就不该跨请求共享。
 */
public final class ToolCallBudget {
    /** 默认上限。关注清单、差异比对这类逐岗评估的扇出都按它约束。 */
    public static final int DEFAULT_LIMIT = 50;

    private final int limit;
    private int spent;

    private ToolCallBudget(int limit) {
        if (limit < 1) throw new IllegalArgumentException("budget must allow at least one call");
        this.limit = limit;
    }

    public static ToolCallBudget of(int limit) { return new ToolCallBudget(limit); }

    public static ToolCallBudget standard() { return new ToolCallBudget(DEFAULT_LIMIT); }

    /**
     * 申请一次调用额度。
     *
     * @return 还有额度则为 true 并计入；已耗尽则为 false，且不再增长——
     *         调用方据此决定是报告"未刷新"还是拒绝给出结论。
     */
    public boolean tryConsume() {
        if (spent >= limit) return false;
        spent++;
        return true;
    }

    public boolean exhausted() { return spent >= limit; }

    public int spent() { return spent; }

    public int limit() { return limit; }
}
