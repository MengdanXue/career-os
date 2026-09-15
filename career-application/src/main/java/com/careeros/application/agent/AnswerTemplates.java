package com.careeros.application.agent;

import com.careeros.application.agent.AgentTooling.Observation;
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
 *
 * <p><b>模板名还是模型自己选的，所以每条模板都要写明它的适用条件。</b>
 * "这个范围内没有找到岗位。"——查到了三个也能这么收尾：句子是程序渲染的，
 * 叙述校验器也挑不出毛病，它没有判定词、没有分数、没有数字。
 * 能拆穿它的只有一件事：这条模板断言的东西，它点名的那几条结果支不支持。
 * 所以适用条件写在 {@link Evidence} 里，由执行器拿点名的观察去核。
 */
public final class AnswerTemplates {
    private AnswerTemplates() {}

    /** 收尾。每一条都只做连接，事实一律在后面的确定性事实块里。 */
    public enum Closing {
        /** 查到了一批岗位。 */
        RANKED_LISTING("下面是这个范围内的岗位，按稳定性排序；资格、限制条件和截止日见下方事实块。",
            Evidence.SEARCH_FOUND_JOBS),
        /** 这个范围里什么都没有。 */
        NOTHING_IN_SCOPE("这个范围内没有找到岗位。", Evidence.SEARCH_FOUND_NOTHING),
        /** 针对单个岗位作答。 */
        SINGLE_JOB("下面是这个岗位的情况；资格、限制条件和截止日见下方事实块。", Evidence.ONE_JOB_READ),
        /** 还有资料项没确认，结论要等确认之后才会变。 */
        PENDING_FIRST("还有资料项没有确认，确认之后这些岗位的结论才会更新。", Evidence.PENDING_ITEMS_LISTED),
        /** 关注清单及其变化。 */
        WATCHLIST_STATE("下面是你关注的岗位，以及自上次查看以来的变化。", Evidence.WATCHLIST_READ);

        private final String text;
        private final Evidence requires;
        Closing(String text, Evidence requires) { this.text = text; this.requires = requires; }
        String render(Map<String, String> slots) { return slots.isEmpty() ? text : null; }
        /** 这个模板认得哪些槽。多给一个就是没读懂，整段作废。 */
        public List<String> slots() { return List.of(); }
        /** 这条模板断言了什么，因而要求点名的结果里有什么。 */
        public Evidence requires() { return requires; }
    }

    /**
     * 追问。问清楚用户要什么，不夹带任何判断。
     *
     * <p>追问本来不需要依据——"你想看哪个城市的岗位？"没有说任何关于世界的话。
     * 但有的追问里<b>带着断言</b>：{@link #BROADEN_SCOPE} 那句"这个范围内没有找到岗位"，
     * 一次查询都没做过的时候它凭空成立，而用户会以为系统查过了。
     * 带断言的追问和收尾一样，要有点名的结果撑着。
     */
    public enum Question {
        WHICH_LOCATION("你想看哪个城市或区县的岗位？", Evidence.NONE),
        BROADEN_SCOPE("这个范围内没有找到岗位，要不要放宽城市或职位类别？", Evidence.SEARCH_FOUND_NOTHING),
        WHICH_ORDINAL("你指的是上面列表里的第几个？", Evidence.NONE),
        /**
         * 唯一带槽的一条：要确认的是哪一项资料。槽位取的是封闭枚举，不是自由文本。
         *
         * <p>它的适用条件不在这里：光有"读到过一份待确认清单"不够，
         * 问的那一项必须真的在清单里，并且要绑定到提出它的岗位与用户看到问题时的资料版本。
         * 那三样由执行器解析成 {@link AgentTooling.FactBinding}。
         */
        CONFIRM_FACT("要不要现在确认「{fact}」？", Evidence.NONE, "fact"),
        RETRY_LATER("这次读不到，要不要稍后再试？", Evidence.READ_FAILED);

        private final String text;
        private final Evidence requires;
        private final List<String> slots;
        Question(String text, Evidence requires, String... slots) {
            this.text = text;
            this.requires = requires;
            this.slots = List.of(slots);
        }
        public List<String> slots() { return slots; }
        /** 这条追问断言了什么，因而要求点名的结果里有什么。 */
        public Evidence requires() { return requires; }

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

    /**
     * 一条模板要成立，点名的那几条结果里必须有什么。
     *
     * <p>这是模板的适用条件，不是措辞检查。措辞早就由程序渲染了；能出错的只剩"选错了哪一条"。
     * 选错的那句话读起来完全正常——"这个范围内没有找到岗位。"——只有拿它断言的东西
     * 去对点名的结果，才看得出它在说一件没发生的事。
     */
    public enum Evidence {
        /** 不对世界作任何断言。纯澄清用这一档，它不需要任何结果撑着。 */
        NONE("", "") {
            boolean satisfiedBy(List<Observation> cited) { return true; }
        },
        /** 断言"这个范围内有这些岗位"。 */
        SEARCH_FOUND_JOBS("点名的结果里没有一次查到了岗位的查询",
            "依据里要有一次查到了岗位的 search_jobs") {
            boolean satisfiedBy(List<Observation> cited) {
                return cited.stream().anyMatch(o -> SEARCH_JOBS.equals(o.tool()) && count(o) > 0);
            }
        },
        /**
         * 断言"这个范围内没有岗位"。
         *
         * <p>要求三样，缺一不可：<b>查过</b>、<b>查完了</b>、<b>一个都没查到</b>。
         * 没查过时这句话凭空成立；查到了三个时它直接是假的；
         * <b>没查完时它比前两种更难看出来</b>——预算用完，这一页一个都没评估出来，
         * {@code count} 照样是 0，而范围里还剩七个没看。用户照着这句话会以为宁波没有岗位，
         * 其实系统只是没钱把它看完。完整性不明（结果里根本没有 complete 这个字段）也算没查完：
         * 把"没说"当成"查完了"，是拿一个没人给过的保证去下结论。
         */
        SEARCH_FOUND_NOTHING("点名的结果里没有一次查过、查完、且一个岗位都没查到的查询",
            "依据里要有一次查过、查完、且一个岗位都没查到的 search_jobs") {
            boolean satisfiedBy(List<Observation> cited) {
                return cited.stream().anyMatch(o -> SEARCH_JOBS.equals(o.tool())
                    && count(o) == 0 && finished(o));
            }
        },
        /** 断言"下面是这个岗位的情况"——指的是某一个岗位，一份列表撑不起它。 */
        ONE_JOB_READ("点名的结果里没有哪一个岗位的结论", "依据里要有一次 job_facts") {
            boolean satisfiedBy(List<Observation> cited) {
                return cited.stream().anyMatch(o -> JOB_FACTS.equals(o.tool()));
            }
        },
        /**
         * 断言"还有资料项没有确认"。
         *
         * <p>只有待确认清单能证明它。关注清单是用户自己收藏的岗位，待确认项是规则算出来缺的字段，
         * 两件事；拿空的关注清单当依据，那句"确认之后这些岗位的结论才会更新"没有任何东西撑着，
         * 而用户会照着它去找一份根本不存在的清单。
         */
        PENDING_ITEMS_LISTED("点名的结果里没有一份列出了待确认项的清单",
            "依据里要有一份列出了待确认项的 pending_confirmations") {
            boolean satisfiedBy(List<Observation> cited) {
                return cited.stream().anyMatch(o -> PENDING_CONFIRMATIONS.equals(o.tool()) && count(o) > 0);
            }
        },
        /** 断言"下面是你关注的岗位"。 */
        WATCHLIST_READ("点名的结果里没有读过关注清单", "依据里要有一次 watchlist") {
            boolean satisfiedBy(List<Observation> cited) {
                return cited.stream().anyMatch(o -> WATCHLIST.equals(o.tool()));
            }
        },
        /**
         * 断言"这次读不到"。
         *
         * <p>什么都没坏的时候问一句"要不要稍后再试"，用户会以为出了故障而白等一轮。
         * 所以它不在"纯澄清"那一档——那一档是"你想看哪个城市"，不声称世界上发生过任何事。
         *
         * <p>这是唯一一档<b>拿失败当依据</b>的：点名的那条要么本身就是失败的观察，
         * 要么虽然成功、但里面明说有读不到的部分（关注清单里算不出当前结论的那几条，
         * 或者没查完的那一页）。只在这一档放开——放开一个口子不等于放开所有，
         * 一次失败的查询照样撑不起"下面是这个范围内的岗位"。
         */
        READ_FAILED("点名的结果里没有一条说明这次读不到", "依据里要有一次失败的、或明说有读不到部分的读取") {
            boolean satisfiedBy(List<Observation> cited) {
                return cited.stream().anyMatch(o -> !o.ok() || unreadable(o) > 0 || incomplete(o));
            }
            public boolean acceptsFailures() { return true; }
        };

        private final String unmet;
        private final String requirement;
        Evidence(String unmet, String requirement) {
            this.unmet = unmet;
            this.requirement = requirement;
        }

        /** 条件不成立时说给调用方听的那句话。 */
        public String unmet() { return unmet; }

        /**
         * 正面说法，渲染进发给模型的协议。
         *
         * <p>条件只写在校验里、不告诉模型，它就会反复选一条永远被拒的模板——
         * 看起来像"模型不会收尾"，其实是从没告诉过它什么时候能用哪一条。
         */
        public String requirement() { return requirement; }

        /** @param cited 这段话点名的那几条观察，已经确认存在且成功 */
        abstract boolean satisfiedBy(List<Observation> cited);

        /** 对外的入口，顺带把"一条都没点名"这种情形收进来。 */
        public boolean holdsFor(List<Observation> cited) {
            return satisfiedBy(cited == null ? List.of() : cited);
        }

        /**
         * 点名的依据里允不允许出现失败的观察。
         *
         * <p>默认不允许：失败的观察里没有任何结论可用。只有"这次读不到"那一档反过来，
         * 它要的就是失败本身。
         */
        public boolean acceptsFailures() { return false; }

        /** 结果里的数量。取不到就当 -1——"没有这个字段"不等于"数量是 0"。 */
        static int count(Observation observation) {
            Object value = observation.data().get("count");
            return value instanceof Number number ? number.intValue() : -1;
        }

        /**
         * 这次读取有没有把范围走完。
         *
         * <p>没有 complete 这个字段时返回 false：这条观察根本没交代自己查完没有，
         * 当成查完了就是拿一个没人给过的保证去下结论。
         */
        static boolean finished(Observation observation) {
            return Boolean.TRUE.equals(observation.data().get("complete"));
        }

        /**
         * 这次读取<b>明说</b>自己没走完。
         *
         * <p>和 {@link #finished} 不是互为反面：没有 complete 这个字段时，
         * {@code finished} 是 false（没人保证过查完了，所以不许断言"没有岗位"），
         * 而这里是 false（也没人说过出了问题，所以不许断言"读不到"）。
         * 两边都朝着"不替系统说话"的方向取值。
         */
        static boolean incomplete(Observation observation) {
            return Boolean.FALSE.equals(observation.data().get("complete"));
        }

        /** 读到了、但有几条算不出当前结论。算不出来和读不到，对用户是一回事。 */
        static long unreadable(Observation observation) {
            Object value = observation.data().get("unreadableCount");
            return value instanceof Number number ? number.longValue() : 0L;
        }
    }

    /** 工具名在这里也要用一次。与注册表同名，写错了会被 {@code AnswerTemplatesTest} 当场发现。 */
    static final String SEARCH_JOBS = "search_jobs";
    static final String JOB_FACTS = "job_facts";
    static final String PENDING_CONFIRMATIONS = "pending_confirmations";
    static final String WATCHLIST = "watchlist";

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
