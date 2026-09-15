# 冻结样本的判据自己也得站得住（2026-09-15）

承 `ccd6a57`。上一轮把那个类修得能加载了，但**判据本身是松的**——
两处松法都会让样本在模型选错的时候照样绿。不碰生产行为，不碰四条样本的问题定义。

---

## 先把判据抽出来，才测得了它

判据原先是 `ModelPlannerLiveSampleTest` 的一个私有静态方法，而那个类默认被 surefire 排除。
**判据藏在一个默认不跑的类的私有方法里，写松了没有人会知道。**
所以先原样搬到 `FrozenSampleChecks`（逐字搬，不改逻辑），红也就打在真正出货的那段逻辑上，
不是打在一个为了好看重写的稻草人上。

## 一、工具炸了不能算"参数没问题"

```java
} catch (RuntimeException blewUp) {
    // 下游被这里刻意打断（job_facts 的假评估器），参数本身没问题。
    return;
}
```

写这句是为了容忍一件具体的事：假的 `job_facts` 评估器会故意抛异常打断下游，
那时参数其实已经过了工具的校验。可这一句同时把**所有**异常都算成了通过——
`search_jobs` 里一个 NPE、目录渲染里一个 IndexOutOfBounds，样本照样绿。

改法：专用异常 `FrozenSampleChecks.SampleStop`，只容忍这一种；
两处假评估器（`job_facts` 的、空关注清单的）改抛它。别的 `RuntimeException` 包成
`AssertionError` 抛出去——包一层不是为了好看，是为了报告里看得出**哪一步、哪个工具、什么异常**，
而不是一个裸奔的堆栈。

## 二、模板名合法 ≠ 这一步在生产里成立

判据原先对 FINISH／ASK 只查两件事：模板名在不在封闭清单里、BASIS 有没有越界。
两项都过，却漏掉最要紧的一项——**这条模板在这份结果下适不适用**。

一次查完了、一个岗位都没查到的 `search_jobs`，撑不起 `PENDING_FIRST`
（"还有资料项没有确认"）——那句话要的是一份非空的待确认清单。
线上的执行器会拒掉它；而这套自判的判据会算它通过。
第四条样本额外挡了 `RANKED_LISTING` / `SINGLE_JOB` / `WATCHLIST_STATE` 三个，
`PENDING_FIRST` 正好从两道缝里都漏过去。

改法：**走执行器自己那一份**。`AgentExecutor` 新增一个公开入口

```java
public List<String> validate(PlannerStep step, List<Observation> observations, SessionContext session)
```

它内部调的是 `run()` 用的同一个 `renderClosing` / `renderQuestion` / `checkFinish` / `checkAsk`，
一行判定逻辑都没有重写，也没有改变 `run()` 的任何行为。判据于是自动覆盖模板适用条件、
依据成立与否、叙述校验和确认绑定——以后生产那边收紧什么，样本判据跟着收紧，不会漂开。

> 这是本轮**唯一**动到的生产文件。改动是纯新增的一个入口，不改既有行为——
> 这正是你给的第二个选项（"抽出同源的 package-visible 校验入口"）；
> 因为样本在 career-web、执行器在 career-application，跨模块，所以是 public 而不是 package-visible。
> 如果你更希望一行生产代码都不动，另一条路是在测试里直接调
> `AnswerTemplates.Closing.requires().holdsFor(...)`——但那要在测试里重写一遍
> "点名的是哪几条观察、失败的观察算不算数"的解析，**又是两份实现**，正是这个项目一路在修的那种毛病。

## 先红后绿

红（`docs/audits/evidence/2026-09-16-sample-checks-red.txt`，6 跑 2 败）：

```
aPlainRuntimeExceptionFromAToolIsAFailureNotAPass        Expecting code to raise a throwable.
anIrrelevantButLegalTemplateOnAnEmptySearchIsAFailure    Expecting code to raise a throwable.
```

两条守护用例当时就绿，收紧之后仍绿：专用停止点照样放过去；空搜索选 `NOTHING_IN_SCOPE` 照样通过。

绿：`ModelPlannerLiveSampleFixtureTest` 6/6。

## 两条必跑的确认

```
默认剖面      ModelPlannerLiveSampleFixtureTest   Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
llm-integration（无 key）  ModelPlannerLiveSampleTest  Tests run: 4, Failures: 0, Errors: 0, Skipped: 4
```

类能加载，四条样本被选中并停在 `assumeTrue`。**没有 key 时是 4 skipped + BUILD SUCCESS**——
授权环境里唯一算数的仍然是 `Skipped: 0`。

## 本轮验收

| 模块 | 结果 |
| --- | --- |
| career-domain | 194 通过 |
| career-application | 360 通过 |
| career-infrastructure | 405 通过，52 跳过 |
| career-web | 132 通过，22 跳过 |
| career-ui (vitest) | 未改动，未重跑 |

career-web 从 128 到 132：新增 4 条判据用例（2 条反例 + 2 条守护）。
计数取自清空 `target/surefire-reports` 之后的一次完整跑。

浏览器脚本本轮未改动、未重跑：没有触碰前后端行为。

## 状态

- 联网样本仍然**阻塞**：出网 403 拒绝 CONNECT，本环境执行不了，未绕行、未索要 key。
- 本轮不包含、继续单列：按候选人授权、原环境部署、全场景生产可靠性。
