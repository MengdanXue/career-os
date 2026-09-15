package com.careeros.application.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 模板的适用条件是按工具名判定的，所以那几个名字必须和注册表里的一模一样。
 *
 * <p>写错一个字母不会有任何报错：那条模板的适用条件会变成永远不成立，
 * 于是本来正常的收尾被拒，页面回落到事实块——看起来像"模型不会收尾"。
 */
class AnswerTemplatesTest {

    @Test void theToolNamesUsedByTemplateConditionsMatchTheRegistry() {
        var registered = new AgentExecutor(List.of(
            ReadOnlyTools.searchJobs((candidateId, query, now, budget) -> {
                throw new UnsupportedOperationException("这个用例只看工具名");
            }, Clock.systemUTC()),
            ReadOnlyTools.jobFacts((candidateId, jobId, now) -> {
                throw new UnsupportedOperationException("这个用例只看工具名");
            }, Clock.systemUTC()),
            ReadOnlyTools.pendingConfirmations((candidateId, budget) -> {
                throw new UnsupportedOperationException("这个用例只看工具名");
            }),
            ReadOnlyTools.watchlist(new com.careeros.application.personal.JobWatchlistService(
                new com.careeros.application.personal.JobWatchlistPorts.Watchlist() {
                    public java.util.List<com.careeros.application.personal.JobWatchlistPorts.WatchedJob>
                        findByCandidate(java.util.UUID candidateId) { return List.of(); }
                    public java.util.Optional<com.careeros.application.personal.JobWatchlistPorts.WatchedJob>
                        find(java.util.UUID candidateId, java.util.UUID jobPostingId) {
                        return java.util.Optional.empty();
                    }
                    public com.careeros.application.personal.JobWatchlistPorts.WatchedJob save(
                        com.careeros.application.personal.JobWatchlistPorts.WatchedJob entry) { return entry; }
                    public void remove(java.util.UUID candidateId, java.util.UUID jobPostingId) {}
                },
                (candidateId, jobId, now) -> { throw new UnsupportedOperationException("这个用例只看工具名"); },
                Clock.systemUTC()), Clock.systemUTC()))).registeredTools();

        assertThat(registered).contains(AnswerTemplates.SEARCH_JOBS, AnswerTemplates.JOB_FACTS,
            AnswerTemplates.PENDING_CONFIRMATIONS, AnswerTemplates.WATCHLIST);
    }

    /** 每条模板都要说清它断言了什么。漏一条，那条模板就又变回"模型选了就算数"。 */
    @Test void everyTemplateDeclaresWhatItAsserts() {
        for (var closing : AnswerTemplates.Closing.values()) {
            assertThat(closing.requires()).as("收尾模板 %s 没有声明适用条件", closing).isNotNull();
        }
        for (var question : AnswerTemplates.Question.values()) {
            assertThat(question.requires()).as("追问模板 %s 没有声明适用条件", question).isNotNull();
        }
    }
}
