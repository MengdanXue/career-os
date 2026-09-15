package com.careeros.application.agent;

import com.careeros.domain.CandidateFacts.CandidateFactKey;
import java.util.List;
import java.util.Map;

/**
 * 用户可见的收尾与追问，收束成有限的几句。
 *
 * <p><b>为什么不再让模型自己写这句话。</b> 上一版靠词表拦"适合你""建议报考"那几种说法，
 * 可复核包里那五条——"你这条线基本没什么硬门槛挡着""你把学历证明补齐就能报名了"
 * "以你的条件，进面基本没问题"——一个数字、一个判定词都没有，词表全拦不住。
 * 往词表里加词永远慢模型一步：能说的句子是无穷的，词表是有限的。
 *
 * <p>所以模型现在只能<b>选一个模板</b>，再给结构化引用（依据第几条结果、哪个岗位、哪个资料项）。
 * 句子由程序渲染。那五条不是被检测出来的，是写不出来的。
 *
 * <p>代价是明说的：模型少了措辞上的自由，答复读起来更像固定句式。
 * 这一版认为这个代价值得——用户看到的每一句判断都必须是程序算出来的。
 */
public final class AnswerTemplates {
    private AnswerTemplates() {}

    /** 收尾。每一条都只做连接，事实一律在后面的确定性事实块里。 */
    public enum Closing {
        /** 查到了一批岗位。 */
        RANKED_LISTING("下面是这个范围内的岗位，按稳定性排序；资格、限制条件和截止日见下方事实块。"),
        /** 这个范围里什么都没有。 */
        NOTHING_IN_SCOPE("这个范围内没有找到岗位。"),
        /** 针对单个岗位作答。 */
        SINGLE_JOB("下面是这个岗位的情况；资格、限制条件和截止日见下方事实块。"),
        /** 还有资料项没确认，结论要等确认之后才会变。 */
        PENDING_FIRST("还有资料项没有确认，确认之后这些岗位的结论才会更新。"),
        /** 关注清单及其变化。 */
        WATCHLIST_STATE("下面是你关注的岗位，以及自上次查看以来的变化。");

        private final String text;
        Closing(String text) { this.text = text; }
        String render(Map<String, String> slots) { return slots.isEmpty() ? text : null; }
        /** 这个模板认得哪些槽。多给一个就是没读懂，整段作废。 */
        public List<String> slots() { return List.of(); }
    }

    /** 追问。问清楚用户要什么，不夹带任何判断。 */
    public enum Question {
        WHICH_LOCATION("你想看哪个城市或区县的岗位？"),
        BROADEN_SCOPE("这个范围内没有找到岗位，要不要放宽城市或职位类别？"),
        WHICH_ORDINAL("你指的是上面列表里的第几个？"),
        /** 唯一带槽的一条：要确认的是哪一项资料。槽位取的是封闭枚举，不是自由文本。 */
        CONFIRM_FACT("要不要现在确认「{fact}」？", "fact"),
        RETRY_LATER("这次读不到，要不要稍后再试？");

        private final String text;
        private final List<String> slots;
        Question(String text, String... slots) { this.text = text; this.slots = List.of(slots); }
        public List<String> slots() { return slots; }

        String render(Map<String, String> given) {
            if (!given.keySet().equals(new java.util.HashSet<>(slots))) return null;
            String rendered = text;
            for (String slot : slots) {
                String value = resolve(slot, given.get(slot));
                if (value == null) return null;
                rendered = rendered.replace("{" + slot + "}", value);
            }
            return rendered;
        }
    }

    /** 槽位取值只能来自封闭枚举。认不出来就作废——渲染出一个看不懂的字段名比不渲染更糟。 */
    private static String resolve(String slot, String value) {
        if (!"fact".equals(slot) || value == null) return null;
        try {
            return label(CandidateFactKey.valueOf(value.strip().toUpperCase()));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String label(CandidateFactKey key) {
        return switch (key) {
            case POLITICAL_AFFILIATION -> "政治面貌";
            case GENDER -> "性别";
            case EMPLOYER_SETTLEMENT_AT_APPLICATION -> "报名时是否已落实工作单位";
            case SOCIAL_INSURANCE_AT_APPLICATION -> "报名时社保缴纳状态";
            default -> key.name();
        };
    }
}
