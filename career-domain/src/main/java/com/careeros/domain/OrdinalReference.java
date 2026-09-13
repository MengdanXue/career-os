package com.careeros.domain;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从提问里认出"第 N 个"。
 *
 * <p>这个解析器的难点不是认出序数，是**不要认错**。中文里"第一"开头的固定词不少，
 * 而且全都跟排序无关：
 *
 * <ul>
 *   <li>第一学历——指的是初始学历，是候选人资料里的字段</li>
 *   <li>第二学士学位——一种学位类型</li>
 *   <li>第一志愿——报考志愿顺序</li>
 *   <li>第一批、第二批——招聘批次</li>
 * </ul>
 *
 * <p>把"我的第一学历是本科"当成"第 1 个岗位"，系统会一本正经地去讲另一件事，
 * 而用户看不出它误解了。所以只在序数后面跟着计量词（个／条／项／家）、
 * 或者序数独立成句时才认，其余一概不认——认不出来只是走普通查询，认错才是真的坏。
 */
public final class OrdinalReference {
    private OrdinalReference() {}

    /** 序数后面跟计量词：第二个、第3条、第一项。 */
    private static final Pattern WITH_COUNTER =
        Pattern.compile("第\\s*([0-9０-９一二三四五六七八九十两]{1,3})\\s*(个|条|项|家)");

    /**
     * 序数独立使用：句末的"第二"，或后面直接跟疑问／评价词的"第二怎么样"。
     * 不允许后面紧跟别的汉字，否则"第一学历"会被认成序数 1。
     */
    private static final Pattern STANDALONE = Pattern.compile(
        "第\\s*([0-9０-９一二三四五六七八九十两]{1,3})\\s*(?:$|[，。？！,.?!\\s]|怎么样|呢|吗|如何|详细|展开)");

    private static final Map<Character, Integer> DIGITS = Map.ofEntries(
        Map.entry('零', 0), Map.entry('一', 1), Map.entry('二', 2), Map.entry('两', 2),
        Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6),
        Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9)
    );

    /**
     * @return 用户说的序号，从 1 开始；认不出来时为空。
     *         为空表示"这句话不是在指某一个岗位"，不是"指了但没找到"——后者由会话去回答。
     */
    public static Optional<Integer> parse(String question) {
        if (question == null || question.isBlank()) return Optional.empty();
        Matcher counter = WITH_COUNTER.matcher(question);
        if (counter.find()) return toOrdinal(counter.group(1));
        Matcher standalone = STANDALONE.matcher(question);
        if (standalone.find()) return toOrdinal(standalone.group(1));
        return Optional.empty();
    }

    private static Optional<Integer> toOrdinal(String token) {
        String normalized = token.strip();
        if (normalized.isEmpty()) return Optional.empty();
        Integer value = normalized.chars().allMatch(OrdinalReference::isArabicDigit)
            ? arabic(normalized) : chinese(normalized);
        if (value == null || value < 1 || value > 99) return Optional.empty();
        return Optional.of(value);
    }

    private static boolean isArabicDigit(int codePoint) {
        return (codePoint >= '0' && codePoint <= '9') || (codePoint >= '０' && codePoint <= '９');
    }

    private static Integer arabic(String token) {
        var digits = new StringBuilder();
        for (char character : token.toCharArray()) {
            digits.append(character >= '０' ? (char) ('0' + character - '０') : character);
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 只处理 1–99：十、十一、二十、二十三。岗位列表不会长到需要更大的数。 */
    private static Integer chinese(String token) {
        int tens = token.indexOf('十');
        if (tens < 0) {
            if (token.length() != 1) return null;
            return DIGITS.get(token.charAt(0));
        }
        Integer high = tens == 0 ? 1 : DIGITS.get(token.charAt(0));
        if (high == null) return null;
        String rest = token.substring(tens + 1);
        if (rest.isEmpty()) return high * 10;
        if (rest.length() != 1) return null;
        Integer low = DIGITS.get(rest.charAt(0));
        return low == null ? null : high * 10 + low;
    }
}
