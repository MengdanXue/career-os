package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * 冻结样本那个类<b>至少要能加载起来</b>。
 *
 * <p>为什么单独立一条：{@code ModelPlannerLiveSampleTest} 带着
 * {@code @Tag("llm-integration")}，默认被 surefire 排除，所以它坏了没有任何人会知道。
 * 它确实坏过——静态字段里给 {@code ReadOnlyTools.watchlist} 传了 null，
 * 那个方法对 null 直接抛 NPE，于是整个类的静态初始化就炸了：
 * 四条样本一条都到不了 {@code assumeTrue}，全部报 ExceptionInInitializerError。
 * 这个毛病一直带到冻结版本 6dc7f69，是在准备联网跑样本时才被发现的。
 *
 * <p>"默认不跑"等于"从没验过"。这一条不需要 key、不需要出网，只做一件事：
 * 把那个类初始化起来，并核对它的工具目录还是线上那四个。
 * 目录少一个工具，发给模型的那份就和线上不一样，样本量的就不再是线上那套工具面。
 */
class ModelPlannerLiveSampleFixtureTest {

    @Test void theFrozenSampleClassLoadsWithoutABlowUp() {
        assertThatCode(() -> Class.forName(ModelPlannerLiveSampleTest.class.getName(), true,
            ModelPlannerLiveSampleTest.class.getClassLoader()))
            .as("冻结样本类连静态初始化都过不去，四条样本一条也跑不了")
            .doesNotThrowAnyException();
    }

    @Test void theSampleToolCatalogueIsTheSameFourToolsAsProduction() {
        assertThat(ModelPlannerLiveSampleTest.toolNamesForFixtureCheck())
            .containsExactly("search_jobs", "job_facts", "pending_confirmations", "watchlist");
    }
}
