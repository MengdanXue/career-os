package com.careeros.domain;

import static com.careeros.domain.RecruitmentLifecycle.Stage.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecruitmentLifecycleTest {
    @Test
    void classifiesEverySupportedStageWithoutTreatingAnInitialNoticeAsLifecycle() {
        assertThat(RecruitmentLifecycle.classify("2025年事业单位公开招聘资格复审通知"))
            .containsExactly(QUALIFICATION_REVIEW);
        assertThat(RecruitmentLifecycle.classify("2025年事业单位公开招聘笔试成绩公告"))
            .containsExactly(SCORE_RESULT);
        assertThat(RecruitmentLifecycle.classify("2025年事业单位公开招聘面试通知"))
            .containsExactly(INTERVIEW);
        assertThat(RecruitmentLifecycle.classify("2025年事业单位公开招聘体检通知"))
            .containsExactly(PHYSICAL_EXAM);
        assertThat(RecruitmentLifecycle.classify("2025年事业单位公开招聘考察通知"))
            .containsExactly(INVESTIGATION);
        assertThat(RecruitmentLifecycle.classify("2025年事业单位公开招聘拟聘用人员公示"))
            .containsExactly(PUBLICATION);
        assertThat(RecruitmentLifecycle.classify("2025年事业单位公开招聘办理聘用手续通知"))
            .containsExactly(APPOINTMENT);
        assertThat(RecruitmentLifecycle.classify("2025年事业单位公开招聘工作人员公告"))
            .isEmpty();
    }

    @Test
    void keepsAllStagesWhenOneOfficialNoticeCoversMultipleSteps() {
        assertThat(RecruitmentLifecycle.classify(
            "杭州市西湖区2025年度部分事业单位公开招聘工作人员体检、考察对象名单"))
            .containsExactly(PHYSICAL_EXAM, INVESTIGATION);
        assertThat(RecruitmentLifecycle.classify(
            "杭州市西湖区2025年度部分事业单位公开招聘入围面试人员及笔试成绩公告"))
            .containsExactly(SCORE_RESULT, INTERVIEW);
    }

    @Test
    void derivesTheSameCampaignStemForInitialAndLifecycleNotices() {
        assertThat(RecruitmentLifecycle.campaignStem(
            "杭州市西湖区2025年度部分事业单位公开招聘工作人员公告"))
            .isEqualTo("杭州市西湖区2025年度部分事业单位公开招聘工作人员");
        assertThat(RecruitmentLifecycle.campaignStem(
            "关于杭州市西湖区2025年度部分事业单位公开招聘工作人员体检、考察对象名单的通知"))
            .isEqualTo("杭州市西湖区2025年度部分事业单位公开招聘工作人员");
    }

    @Test
    void derivesTheSameStemWhenAnOfficialLifecycleTitleMovesThePublicInstitutionPhrase() {
        String initial = RecruitmentLifecycle.campaignStem(
            "2026年杭州市拱墅区卫生健康局事业单位公开招聘工作人员公告");
        String scoreResult = RecruitmentLifecycle.campaignStem(
            "2026年拱墅区卫生健康局公开招聘事业单位工作人员综合成绩公示");

        assertThat(scoreResult).isEqualTo(initial);
    }

    @Test
    void refusesAStemWithoutRecruitmentMeaning() {
        assertThat(RecruitmentLifecycle.campaignStem("杭州市西湖区2025年度工作总结")).isEmpty();
        assertThat(RecruitmentLifecycle.campaignStem("关于公布成绩的通知")).isEmpty();
    }
}
