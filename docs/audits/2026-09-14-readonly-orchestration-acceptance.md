# 只读工具编排：不依赖 key 的修复与真实前后端验收

日期：2026-09-14
范围：上一轮遗留的五项不依赖模型凭据的缺陷 + 已有交互断点
不在范围：多 Agent、Forecast、新框架

## 为什么单独记这一份

上一轮的收尾把状态写成了"唯一只差 key"。那是不成立的：下面五项与模型凭据无关，
其中三项在真实前后端跑起来之前，单元测试全是绿的。

## 一、模型首轮实际收到工具目录与参数定义

**此前**：`ModelPlanner.PROTOCOL` 是一段静态文本，只讲格式，不含任何工具名与参数。
模型只能猜工具名，于是每一步都被执行器拒绝——看起来像"模型不会用工具"，
实际是从没告诉过它有哪些工具。

**现在**：提示由 `ModelPlanner.protocol(state)` 拼出，目录来自执行器的注册表
（`AgentExecutor.toolCatalogue()` → `ToolSpec` → `ToolParameter`），
与工具自身校验参数用的是同一份定义。一个工具都没注册时明说"没有注册任何工具"，
不省略——省略会让模型以为目录只是没写出来。

**真机核对**（`career-os.agent.planner=recorded`，进程日志原样打印首轮提示）：

```
可用工具（只读，全部只能查，不能改）：

- search_jobs：按城市、职位类别、机会分层查岗位，返回排好序的一页结果。
  参数：
    - location（可选）：城市或地区名，按包含匹配，例如 杭州
    - jobFamily（可选）：职位类别；只能取 SOFTWARE、DATA、AI、CYBERSECURITY、…
    - tier（可选）：机会分层；只能取 T1、T2、T3、EXCLUDED
    - limit（可选）：返回几个岗位，1 到 10，默认 5

- job_facts：取一个岗位的硬资格、限制条件与截止日。
  参数：
    - jobId（必填）：岗位的 UUID，取自 search_jobs 或关注清单的结果
- pending_confirmations / watchlist：参数：无
```

## 二、格式错误和非法筛选不再静默变成有效查询

**此前的约定是错的**：`ReadOnlyToolsTest.anUnrecognisedFilterBecomesNoFilterRatherThanAGuess`
断言"认不出来当作没筛选"，理由写的是"不猜"。但那也是猜——猜用户不在乎这个条件。
`tier=T9` 返回的是全量结果，读起来和"T9 就是这些"没有区别。

**现在**：不认识的参数名、不在取值范围内的枚举、非整数或超范围的 limit，一律返回失败观察，
并把允许的取值写进去。该测试已重写为 `anUnrecognisedFilterFailsInsteadOfSilentlyBecomingNoFilter`。

**真机核对**（一次运行里连续四个非法调用，全部被拒且都没有发出查询——每步只花 1 个预算单位，
说明一次逐岗评估都没发生）：

| 模型给的参数 | 结果 |
|---|---|
| `tier=T9` | 失败：参数「tier」的取值「T9」不在允许的范围里。可选：T1、T2、T3、EXCLUDED。 |
| `locaiton=杭州`（打错） | 失败：不认识参数「locaiton」。这个工具只接受：location、jobFamily、tier、limit。 |
| `limit=500` | 失败：参数「limit」的取值「500」超出范围，只能是 1 到 10。 |
| `candidateId=<别人>` | 失败：不认识参数「candidateId」。 |
| `location=杭州`（合法） | 成功：找到 2 个岗位（花 3 个预算单位 = 1 次调用 + 2 次逐岗评估） |

## 三、FINISH 和 ASK 都不能输出无依据结论

叙述校验器管的是"别写数字和判定词"，管不了"凭空说话"：
「这些岗位都挺适合你的」一个数字一个判定词都没有，但它背后一条数据也没有。

新增三条，都在执行器里：

1. 一次成功的工具结果都没有时，`FINISH` 的叙述整段丢弃（`UNGROUNDED_FINISH`）。
2. `ASK` 走与 `FINISH` 同一套叙述校验——只校验 FINISH 的话，判定词换成疑问句就溜出去了。
3. `ASK` 必须是问句。陈述句借追问的通道发出，就绕开了"结论要有依据"。

澄清式追问（"你说的杭州是指市区，还是整个杭州市？"）不需要依据，照常放行。

**真机核对**：

| 模型输出 | 结果 |
|---|---|
| `FINISH 这些岗位都挺适合你的…`（未调任何工具） | narrative 为空，violations=["没有任何成功的工具结果，这段收尾叙述没有依据"] |
| `ASK 这个岗位你是可报的，适配 72 分，要我继续往下看吗？` | question 为空，violations=[数值, 判定词「可报」] |
| `ASK 这些岗位都挺适合你的。` | question 为空，violations=["追问不是一个问句…"] |
| `ASK 你说的杭州是指市区，还是整个杭州市？` | 放行，violations 为空 |

## 四、内部逐岗评估计入共享预算

**此前**：执行器每次工具调用扣 1 个单位，而 `search_jobs` 背后是 N 次逐岗评估，
`pending_confirmations` 走 `rankAll` 最多 100 次。模型调三次工具就能触发上百次评估，
预算写了等于没写。

**现在**：预算对象由执行器交给工具（`ToolContext.budget()`），
`DecisionRankingService.rank/rankAll` 与 `JobWatchlistService.list` 都接受它，一岗一扣。
预算不够时**不静默截断**：排名把 `notAssessed` 报出来并标成不完整；
待确认清单直接拒绝给出——"还差哪些确认"是个结论，少列几项读起来就是"都齐了"。

**真机核对**：同一次运行调了三个工具，`budgetSpent=7`（旧口径是 3）：
`search_jobs` 3（1 + 2 岗）、`pending_confirmations` 3（1 + 2 岗）、`watchlist` 1（1 + 0 岗）。

## 五、超时后的并发与客户端重试有界

`Future.cancel(true)` 只是打个中断标记，卡在 socket 读上的线程收不到。
上一轮只加了 20 秒截止时间，线程池仍是 `newCachedThreadPool`——
模型不可达时每个请求都留下一个收不回来的线程。

- 线程池改为上限 `MAX_CONCURRENT_MODEL_TURNS=8`、`SynchronousQueue`、`AbortPolicy`。
  满了当场拒绝，解析成"这一步没有计划"；排队只是把等待时间藏起来。
- 客户端自身的重试：`spring.ai.retry.max-attempts` 从默认 **10** 降到 2，
  退避 0.5s→2s，`on-client-errors=false`；并补上
  `spring.http.client.connect-timeout=5s` / `read-timeout=10s`——
  没有这两个超时，重试上限也无从生效。

**真机核对**（base-url 指向不可达地址 `10.255.255.1:9`）：
单次请求 11 秒返回 HTTP 200（`PLANNER_FAILED`），此前是 120 秒不返回；
12 个并发请求（超过上限 8）全部 200，总耗时 11 秒，结束后进程内
`agent-model-turn` 线程数为 **0**。

## 六、交互断点：接口可调用 ≠ 用户走得通

上一轮 `/agent-runs` 只有接口，页面上没有任何入口；会话只活在页面内存里。

- **页面接入运行接口**：`AgentRunPanel`，展示结果、轨迹（含被拒的步骤）、
  观察、预算消耗与上限、以及完整工具目录到参数一级。模型未启用时如实写"模型未启用"，
  不拿写死的流程冒充。
- **会话续跑 + 刷新恢复**：`GET /api/v1/candidates/{id}/agent-sessions/{sessionId}`
  取回岗位顺序、待确认事项与资料版本；会话 ID 存 localStorage，刷新后自动接上。
  资料在这期间变了会标 `stale`，页面提示重新查询，不拿旧序号继续问"第二个怎么样"。
- **原键重算按钮**：`RECORDED_RECOMPUTE_DEFERRED` 时给按钮，不再只写一句"稍后重试"。
  用同一把幂等钥匙重放，从重算接着做。
- **单岗待确认事项**：每条问题标出是哪个岗位提出的，并链到那个岗位。
- **候选人归属检查**：`resolveOrdinal`、`profileVersionSeenBy`、`advanceProfileVersion`
  全部改为按候选人取会话；`/agent-runs` 与 `/agent-sessions` 同样核对。
  不存在与不属于本人返回同一个 404，不泄露"存在但不是你的"。

### 顺带修掉的一个盲点

`ProfileConfirmationService.recompute` 吞掉异常是对的（用户的声明不该因为算不动就丢掉），
但连日志都不留，就变成"点重算没反应，谁也不知道为什么"。这次排查正是卡在这里：
补上 WARN 日志后，一行就看出是 `JobNotAdmittedException`——
改 `employment_type` 会触发 `trg_job_admission_employment_identity_change`
把准入降级，而验收脚本的修复命令没有把准入一并恢复。
为此 `career-application` 新增了 `slf4j-api`（只要门面，不引入任何框架，ArchUnit 照旧通过）。

## 验收结果

离线回归（最终代码）：

| 模块 | 结果 |
|---|---|
| career-domain | 194 通过 |
| career-application | 286 通过 |
| career-infrastructure | 405 通过（52 跳过，需要 Docker） |
| career-web | 114 通过（22 跳过） |
| career-ui | 75 通过，tsc 无错 |

真实浏览器 + 真实后端 + 真实 PostgreSQL：

- `e2e/closed-loop.mjs` **15/15**
- `e2e/recompute-retry.mjs` **8/8**（真实注入重算失败并修复）

台账核对：注入失败后原键重试，`profile_confirmation_ledger` 仍只有一条记录，
stage 从 `WRITTEN` 推进到 `RECOMPUTED`，资料版本只推高了一次
（`profile-seed-1` → `profile-b424e795…`）。

## 冻结小样本：脚本已就绪，本环境跑不了

凭据这次拿到了（OpenAI 兼容的 DeepSeek key）。**跑不了的原因不是 key，是出网策略**：
本会话的 egress 代理对 `api.deepseek.com:443` 与 `api.openai.com:443` 都返回
`connect_rejected`（组织策略拒绝 CONNECT），只有 `api.anthropic.com` 可达——
那是本会话自己的凭据通道，不是用户为这个应用授权的凭据，不拿它跑应用流量。

已经落地的、等出网放开就能跑的东西：

- `career-web/src/test/java/com/careeros/ModelPlannerLiveSampleTest.java`
  （`@Tag("llm-integration")`，默认被 surefire 排除）。输入不走数据库——要测的是
  模型的选择，不是数据层；状态用 `PlanningState` 手工冻结，工具目录与线上同源，
  温度置零，所以同一份输入可以反复比对。四个样本：
  1. **最小判据**：同一个问题、同一份目录，只有工具结果不同（有岗位 / 没岗位），
     下一步必须不同。写死流程的实现在这两种输入下会调同一串工具；这条不过，
     就没有理由把它叫作 Agent。
  2. 非法参数被拒之后，模型要改用合法取值，而不是把 `tier=T9` 再报一遍。
  3. 剩余预算只够一次调用时要自己收敛，而不是一直要求调工具直到撞上限。
  4. 收尾文字的违规率——提示词与叙述校验器一旦对不上，回答会次次回落到事实块，
     用户看到的东西变差而测试全绿。
- `spring.ai.openai.base-url` 改为 `${CAREER_OS_AI_BASE_URL:https://api.openai.com}`，
  换任何 OpenAI 兼容端点都不必改代码。

跑法（在出网允许的机器上）：

```
OPENAI_API_KEY=...            CAREER_OS_AI_BASE_URL=https://api.deepseek.com CAREER_OS_AI_MODEL=deepseek-chat mvn -P llm-integration -pl career-web test -Dtest=ModelPlannerLiveSampleTest
```

## 仍然没有做到的事

- **还没有证明模型会依据不同工具结果选择不同下一步。** 上面那条出网策略挡住了。
  `RecordedPlannerConfiguration` 回放的是写死的输出，
  `ScriptedPlannerConfiguration` 的分支也是写死的——两者都**不是 Agent**，
  它们证明的是"这条路径接通了、边界守得住"，不是"模型会选工具"。
- **没有任何形式的按候选人授权。** 本轮补的是"会话 ID 要核对归属"；
  但整套 API 仍是"通过认证的操作员可以对任何 candidateId 操作"，
  路径里的 candidateId 不与登录身份比对。这是既有的全局设计，不是本轮引入的，
  也不在本轮范围内——但不能因为补了会话归属就以为归属已经守住了。
- 一个岗位算不出来仍会让整次排名查询失败（关注清单会降级，排名不会）。这条本轮未动。
- 原环境（GitHub Actions 之外的部署）状态另行报告。
