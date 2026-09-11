package com.careeros.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 只识别公告里写死的性别限定，与 {@link PoliticalRequirementClassifier} 同一口径。
 *
 * <p>把偏好当门槛会凭空滤掉可报的岗位：「男性优先」是招录单位的倾向，不是报名资格。
 * 早先的实现只要文本里出现「男」就判定为限男，「男性优先」「男女不限」「男女各一名」
 * 都会被误读。
 *
 * <p>无法判断时返回空，由调用方落到待确认——不猜。
 */
public final class GenderRequirementClassifier {

    /** 明确表示不构成限制的写法。出现任一即视为无限制。 */
    private static final String[] NOT_A_RESTRICTION = {
        "不限", "均可", "不作要求", "无要求", "无限制"
    };

    /** 表示倾向而非门槛的写法。出现即不构成限制。 */
    private static final String[] PREFERENCE_ONLY = { "优先", "为宜", "较佳" };

    /**
     * @return 公告限定的性别；公告未限定、只表达倾向、或写法无法判断时为空。
     *         空值本身不区分"没限制"和"读不懂"，由调用方结合原文是否存在来区分。
     */
    public Optional<DomainEnums.Gender> hardRequirement(JobPosting job) {
        Objects.requireNonNull(job, "job");
        return hardRequirement(job.genderRequirement());
    }

    public Optional<DomainEnums.Gender> hardRequirement(String requirement) {
        if (requirement == null || requirement.isBlank()) return Optional.empty();
        String compact = requirement.replaceAll("[\\s、：:，,。.；;（）()]", "");
        if (containsAny(compact, NOT_A_RESTRICTION)) return Optional.empty();
        if (containsAny(compact, PREFERENCE_ONLY)) return Optional.empty();

        boolean male = compact.contains("男");
        boolean female = compact.contains("女");
        // 两性都提到（"男女各一名"、"男1女1"）或都没提到，都判断不了限的是哪一边。
        if (male == female) return Optional.empty();
        return Optional.of(male ? DomainEnums.Gender.MALE : DomainEnums.Gender.FEMALE);
    }

    /**
     * 公告是否**明确表示**不限性别。用来把"公告写了不限"和"公告根本没提/没解析出来"
     * 区分开——前者是可以下结论的事实，后者只能待确认。
     */
    public boolean explicitlyUnrestricted(String requirement) {
        if (requirement == null || requirement.isBlank()) return false;
        String compact = requirement.replaceAll("[\\s、：:，,。.；;（）()]", "");
        return containsAny(compact, NOT_A_RESTRICTION) || containsAny(compact, PREFERENCE_ONLY);
    }

    private static boolean containsAny(String value, String[] needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }
}
