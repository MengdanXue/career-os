package com.careeros.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic classification and campaign identity for post-notice recruitment documents. */
public final class RecruitmentLifecycle {
    private static final Pattern RECRUITMENT = Pattern.compile("公开招聘|招聘|招考|选聘|人才引进");
    private static final Pattern LEADING_NOISE = Pattern.compile("^(?:关于|公布|发布|转发)+");
    private static final Pattern TRAILING_NOTICE = Pattern.compile("(?:的)?(?:公告|通知|简章)$");
    private static final Pattern YEAR_FOLLOWED_BY_HANGZHOU = Pattern.compile("^(\\d{4}年(?:度)?)杭州市");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\p{Punct}，。；：、（）()《》〈〉【】\u2014\u2013]+", Pattern.UNICODE_CHARACTER_CLASS);

    private RecruitmentLifecycle() {}

    public static Set<Stage> classify(String title) {
        if (title == null || title.isBlank()) return Set.of();
        var result = new LinkedHashSet<Stage>();
        for (Stage stage : Stage.values()) {
            if (stage.pattern.matcher(title).find()) result.add(stage);
        }
        return Collections.unmodifiableSet(result);
    }

    public static String campaignStem(String title) {
        if (title == null || title.isBlank()) return "";
        String value = title.strip();
        value = LEADING_NOISE.matcher(value).replaceFirst("");
        int lifecycleStart = firstLifecycleMarker(value);
        if (lifecycleStart >= 0) value = value.substring(0, lifecycleStart);
        else value = TRAILING_NOTICE.matcher(value).replaceFirst("");
        value = value.replaceFirst("(?:有关事项|相关事项)$", "")
            .replaceFirst("的$", "");
        value = YEAR_FOLLOWED_BY_HANGZHOU.matcher(value).replaceFirst("$1")
            .replace("公开招聘事业单位", "事业单位公开招聘");
        value = SEPARATORS.matcher(value).replaceAll("");
        return value.length() >= 8 && RECRUITMENT.matcher(value).find() ? value : "";
    }

    private static int firstLifecycleMarker(String value) {
        int earliest = -1;
        for (Stage stage : Stage.values()) {
            var matcher = stage.pattern.matcher(value);
            if (matcher.find() && (earliest < 0 || matcher.start() < earliest)) earliest = matcher.start();
        }
        return earliest;
    }

    public enum Stage {
        QUALIFICATION_REVIEW("资格复审|资格审查|资格确认"),
        WRITTEN_EXAM("笔试(?!成绩|结果|分数)|专业知识测试|考试安排|实践技能测试"),
        SCORE_RESULT("笔试成绩|总成绩|综合成绩|考试成绩|成绩公告|成绩查询|入围面试|入围体检"),
        INTERVIEW("面试"),
        PHYSICAL_EXAM("体检"),
        INVESTIGATION("考察|政审"),
        PUBLICATION("拟聘|公示"),
        APPOINTMENT("办理聘用|正式聘用|录用通知|聘用手续|录用手续");

        private final Pattern pattern;

        Stage(String regex) {
            this.pattern = Pattern.compile(regex);
        }
    }
}
