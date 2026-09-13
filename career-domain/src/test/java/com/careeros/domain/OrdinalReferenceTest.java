package com.careeros.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrdinalReferenceTest {

    // --- 认得出来 ---

    @Test void recognisesAnOrdinalWithACounter() {
        assertThat(OrdinalReference.parse("第二个怎么样？")).contains(2);
        assertThat(OrdinalReference.parse("第3个再说说")).contains(3);
        assertThat(OrdinalReference.parse("第一项的限制是什么")).contains(1);
        assertThat(OrdinalReference.parse("上面第二条展开讲讲")).contains(2);
    }

    @Test void recognisesAStandaloneOrdinal() {
        assertThat(OrdinalReference.parse("第二怎么样")).contains(2);
        assertThat(OrdinalReference.parse("说说第三")).contains(3);
        assertThat(OrdinalReference.parse("第5呢")).contains(5);
    }

    @Test void recognisesFullWidthDigits() {
        assertThat(OrdinalReference.parse("第２个怎么样")).contains(2);
    }

    @Test void recognisesChineseTens() {
        assertThat(OrdinalReference.parse("第十个")).contains(10);
        assertThat(OrdinalReference.parse("第十二个")).contains(12);
        assertThat(OrdinalReference.parse("第二十个")).contains(20);
        assertThat(OrdinalReference.parse("第二十三个")).contains(23);
    }

    // --- 不要认错 ---

    /**
     * 这些是中文里"第一"开头的固定词，跟排序毫无关系。把"我的第一学历是本科"
     * 当成"第 1 个岗位"，系统会一本正经地去讲另一件事，而用户看不出它误解了。
     * 认不出来只是走普通查询，认错才是真的坏。
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "我的第一学历是本科",
        "第一学历有要求吗",
        "第二学士学位算不算",
        "这个岗位是第一志愿",
        "第一批公告什么时候出",
        "第二批还有机会吗",
        "第一作者的论文算吗",
        "第三方机构出具的证明",
    })
    void doesNotMistakeAFixedPhraseForAnOrdinal(String question) {
        assertThat(OrdinalReference.parse(question)).isEmpty();
    }

    @Test void doesNotTreatAPlainYearOrCountAsAnOrdinal() {
        assertThat(OrdinalReference.parse("2026年的岗位有哪些")).isEmpty();
        assertThat(OrdinalReference.parse("给我看5个杭州岗位")).isEmpty();
        assertThat(OrdinalReference.parse("杭州有哪些稳定的信息化岗位？")).isEmpty();
    }

    @Test void anEmptyOrMissingQuestionResolvesNothing() {
        assertThat(OrdinalReference.parse(null)).isEmpty();
        assertThat(OrdinalReference.parse("   ")).isEmpty();
    }

    /** 序号从 1 开始；0 和负数不是序号。 */
    @Test void zeroIsNotAnOrdinal() {
        assertThat(OrdinalReference.parse("第0个")).isEmpty();
        assertThat(OrdinalReference.parse("第零个")).isEmpty();
    }

    /** 超出合理范围的数字宁可不认——岗位列表不会有第 999 个。 */
    @Test void anImplausiblyLargeOrdinalIsNotRecognised() {
        assertThat(OrdinalReference.parse("第999个")).isEmpty();
    }
}
