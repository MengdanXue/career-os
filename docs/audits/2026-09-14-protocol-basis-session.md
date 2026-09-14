# 协议整段解析、依据绑定、会话续跑

日期：2026-09-14
范围：上一轮没有核销的三组问题 + 旧反例转正式回归 + 收紧模型样本
不在范围：多 Agent、Forecast、新框架

## 为什么这三组没有随上一轮一起核销

上一轮交付的目录、预算、有界并发、页面恢复、重算按钮与归属检查都还在，本轮没有重做。
但"不依赖 key 的要求"当时不该整体算完：下面三件事各自都能让系统在看起来正常的情况下出错。

## 一、协议整段解析

**此前**是逐行扫描，看到第一行认识的就返回。三处会静默出错：

| 模型输出 | 旧行为 | 现在 |
|---|---|---|
| `FINISH 下面按稳定性排序。`<br>`其中一处仍需你补充材料后才能定。` | 只取第一行，第二行无声丢失 | 整段保留 |
| `TOOL search_jobs …` + `FINISH …` | 按出现顺序挑第一个 | 整段作废，`PLANNER_FAILED` |
| `TOOL search_jobs …` + `TOOL watchlist` | 只执行第一个 | 整段作废 |

第一条最危险：限制条件常常正好单独成行，删掉之后剩下的读起来比原文更肯定。
后两条是替模型做决定——它到底要查还是要收尾？挑错了照样执行，而且看起来完全正常。

现在的规则：整段收集全部动作行，**不止一个动作就整段作废**；
`FINISH` / `ASK` 的文字一直取到结尾，`WHY` / `BASIS` 是协议字段不算进叙述。

**真机核对**（recorded 规划器，同一个进程连发四次）：

```
A 同一段两个动作        -> PLANNER_FAILED，narrative=None
B 多行叙述 + BASIS 1    -> FINISHED，narrative='下面按稳定性排序。\n其中一处仍需你补充材料后才能定。'
```

## 二、FINISH / ASK 与具体依据绑定

**此前的判据是"这次运行里存在成功的观察"。** 那只说明附近有数据，不说明这段话用了它——
查了关注清单，然后收尾说岗位怎么样，照样通过。

现在协议多一行 `BASIS`：收尾要点名依据的是上面第几条结果，执行器再核对那几条确实存在、确实成功。
追问可以写 `BASIS none` 明说自己是纯澄清（"你说的杭州是指市区还是整个市？"本来就不需要依据），
但不能含糊过去；收尾不接受"纯澄清"——那是追问的选项，不是结束的理由。

**真机核对**：

```
C 没写 BASIS              -> narrative=None，violations=['这段收尾叙述没有点名它依据哪一条工具结果']
D BASIS 指向不存在的第 9 条 -> narrative=None，violations=['这段话点名的依据不成立：没有第 9 条工具结果']
```

响应里的 `groundedOn` 报出点名的那几条，页面写成"这段话依据第 N 条结果"；
没点名时写成"这段话没有点名依据"，不让"没有依据"看起来和"有依据"一样。

## 三、动态执行器真正使用会话上下文

**此前**执行器的签名里根本没有会话：`/agent-runs` 查了会话只为核对归属，然后原样回传 sessionId。
那不是续跑。

现在 `AgentExecutor.run(candidateId, question, planner, SessionContext)`：
上一轮的范围、列表顺序、还没答的资料项既进 `PlanningState`（模型看得到），
也进 `ToolContext`（工具解析序号用）。`job_facts` 因此多了 `ordinal` 参数，
序号只在上一轮记下来的顺序里解析，**从不重新排名**。

**真机核对**（进程日志里首轮提示的状态段）：

```
用户问题：余杭
上一轮的范围：地点=杭州
上一轮列表（用户说"第几个"指的就是这个顺序，不要重新排名）：
  1. jobId=c0000000-0000-4000-8000-000000000001
  2. jobId=c0000000-0000-4000-8000-000000000002
  要它们的标题和结论，用 job_facts（可以直接给 ordinal）。
上一轮问过、用户还没答的资料项：
  - POLITICAL_AFFILIATION：候选人政治面貌尚未确认
  - GENDER：候选人性别尚未确认
```

列表里刻意不编标题：会话只存了 jobId，编一个占位标题会让模型以为它已经知道这是什么岗位。

### 结构化岗位结果与单岗待确认事项

`/agent-runs` 的响应现在带 `jobs` 与 `pendingConfirmations`，**取自工具返回的结构化数据**，
不是模型那段话——让模型复述标题和结论就等于把事实交给它，写错写漏都看不出来。
页面照此渲染：标题、机构、分层、资格、报名截止、限制条件，外加每条待确认标出提出它的岗位。

真机一次运行的响应：

```
jobs: 信息中心技术岗 | NEEDS_CONFIRMATION | T3 | 2026-12-01
      档案管理岗     | NEEDS_CONFIRMATION | T3 | 2026-12-01
pending: POLITICAL_AFFILIATION（…0001）、GENDER（…0002）
groundedOn: [1]
```

### 顺带去掉一处重复

上一轮我新加的 `GET /agent-sessions/{id}` 与既有的 `GET /agent-queries/{id}` 重复，
而且比既有的弱——少了 `answered`。已删除，页面改用既有接口，并把 `stale` 补到它上面。
刷新之后答过的问题标成"已答过"而不是再问一遍：原样回放会让用户分不出"还没答"和"答过了"，
删掉则会让他以为这一条凭空消失了。

## 四、旧反例转正式回归

复核里的两个反例现在是正式用例，断言的是行为，不是 sessionId 被原样回传：

| 反例 | 单元回归 | 浏览器回归 |
|---|---|---|
| Agent 追问后用户只答"余杭" | `aBareDistrictAnswerNarrowsThePreviousScope`：同一个规划器，有会话时收窄到余杭，没会话时只能再问一遍 | `multi-round.mjs`：残句之后仍在同一轮，上一轮的列表还在 |
| 刷新后指向原列表第二个 | `theSecondOneResolvesAgainstTheListingTheUserSaw`：断言问的是上一轮列表的第二个 jobId | `multi-round.mjs`：期望/实得 jobId 逐一比对，并断言它不是第一个 |

另外加了 `anOrdinalWithoutAPreviousListingIsRefusedRatherThanReRanked`：
没有上一轮列表时明说没有，不拿这一轮重新排出来的第二个顶上去。

## 五、收紧四类模型样本

| 要求 | 之前 | 现在 |
|---|---|---|
| 目录从实际注册表生成 | 测试里手写一份 `TOOLS` 常量 | 用真实 `ReadOnlyTools` 建 `AgentExecutor`，目录取 `toolCatalogue()` |
| 动作和参数必须有效 | 只比较"选了什么"，没验过能不能执行 | `assertUsable`：工具要在注册表里，参数要过工具自己的校验；收尾要点名依据且在范围内 |
| 判据与测试名一致 | `mostClosingTextsPass…` 实际断言的是"至少一条通过" | 改成过半 |
| 模型调用有明确时限 | 裸 ChatClient，没有超时 | 走与线上同一条 `boundedTurn` + 有界线程池 |

## 验收结果

离线回归（最终代码）：

| 模块 | 结果 |
|---|---|
| career-domain | 194 通过 |
| career-application | 304 通过 |
| career-infrastructure | 405 通过（52 跳过，需要 Docker） |
| career-web | 114 通过（22 跳过） |
| career-ui | 78 通过，tsc 无错 |

真实浏览器 + 真实后端 + 真实 PostgreSQL：

- `e2e/closed-loop.mjs` **16/16**
- `e2e/multi-round.mjs` **9/9**（两个旧反例）
- `e2e/recompute-retry.mjs` **8/8**（真实注入重算失败并修复）

## 出网阻塞（单列）

冻结小样本仍然跑不了。本会话的 egress 代理对 `api.deepseek.com:443` 与 `api.openai.com:443`
都返回 `connect_rejected`（组织策略拒绝 CONNECT）。按代理的说明，策略拒绝不重试、不绕行。
`ModelPlannerLiveSampleTest` 已按上表收紧，等出网放开或在能出网的机器上直接跑：

```
OPENAI_API_KEY=... CAREER_OS_AI_BASE_URL=https://api.deepseek.com \
CAREER_OS_AI_MODEL=deepseek-chat \
mvn -P llm-integration -pl career-web test -Dtest=ModelPlannerLiveSampleTest
```

## 仍然没有做到的事

- **还没有证明模型会依据不同工具结果选择不同下一步。** 出网被策略挡住，与凭据无关。
  回放规划器与脚本规划器都不是 Agent。
- **没有任何形式的按候选人授权。** 补的是"会话 ID 要核对归属"；整套 API 仍然是
  通过认证的操作员可以对任何 candidateId 操作。既有全局设计，不在本轮范围内。
- 一个岗位算不出来仍会让整次排名查询失败（关注清单会降级，排名不会）。本轮未动。
