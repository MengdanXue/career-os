# 第一阶段放行账本（跨会话核销）

日期：2026-09-14。当前状态：本轮修复已通过本地回归与真实闭环，**不是整阶段放行**。
动态工具选择默认关闭；相同完整 SHA 的推送与 CI 证据另存交付记录，不能用主干旧绿灯代替。

## 依据及证据规则

以用户 2026-09-14 的第一阶段继续指令、2026-09-11 Agent 边界设计、
2026-09-13 真实浏览器审计，以及“Phase 2 完成度审计”跨会话记录为依据。
旧 `NEXT_TASK.md` 的“只读本会话”限制不适用于这次核销；旧记录保留，不以本次记录抹去。
旧提案中的自由叙述、预言“确认后解锁多少岗位”、一次倒出问题等建议不高于最新用户边界。

基线主干完整 SHA：`261e389f4c3b742154a74b56303f3da6f5b74ae0`。
本次使用其独立克隆及功能分支 `codex/stage1-release-gates-20260914`，未切换或替换原部署。
最终代码 SHA、同 SHA CI 与浏览器原始证据索引在交付记录中绑定；没有索引的项不得视为已放行。

## 已有修复：保留、不重复开发

| 原事项 | 已有实现 / 完整 SHA | 本次核销方式 |
| --- | --- | --- |
| 类型化事实、字段与单位、岗位和评估版本绑定 | `AnswerBlock` / `AgentQueryService`; `810c9a21bf63b4c4ba33ef9bfd284d2c88990579` | 保留事实块；本次另关残留自由 phraser 出口 |
| 硬资格五态、未知不当满足 | `EligibilityEvaluator`; `5a4e16db412e479071ee8eb55a567883abaea1f7` | 原领域回归继续执行，不回退到概率资格 |
| 报名时未就业/社保是本人声明 | `EligibilityEvaluator.evaluateFreshGraduateStatus`; `5e83da568ded8882002c353b12f2ac37c684e083` | DECLARED_MET 只支持 CONDITIONAL；UNKNOWN/UNDECLARED 保留；未核实条款的 false/false 漏洞另列本轮修复 |
| 确认写入与幂等账本 | `ProfileConfirmationService`; `0f0ee8130f7d3781b2f34f002705b996a13e5743`; 数据库约束 `b851d40e6a296bca2d7a0eb35c01b4865626066c` | 保留原服务和 V86 主键；前端补动作/重试区分 |
| 部分未知/优先条款判定 | `EligibilityEvaluator` / `GenderRequirementClassifier`; `4f7c911f3bb74e451040e5a22cae606480fda0e8` | 保留已有修复；不能覆盖下述未核实 false/false 和政治替代条件两个遗留分支 |
| 写入先提交，重算失败不能回滚回答 | `ProfileConfirmationController`; `728d6b821346d9e83ac9577291d647aa25e25d6b` | 保留事务边界；真实 DB 故障与按钮恢复核验 |
| 原序号引用 | `OrdinalReference` / `AgentSessionService`; `2a2fdf2f66ed3f7062d6e4e5eac5e1c511627d0c` | 不重新排序代替“第二个”；补归属、适用问题、并发 CAS |
| 会话 GET、部分版本推进和关注入口 | `d40162dc700f4d89efcf245ed58ba8551f87d8ec` | 不把部分实现当整条链完成；本轮补前端恢复和真实刷新断言 |
| 首次关注即建立展示基线 | `JobWatchlistService.watch`; `111ba00304641ea27a150e17a291962f3f508a8b` | 保留，真实空清单新增关注核验 |
| 读取关注不消耗变化，确认带展示版本 | `JobWatchlistService`; `5b469b02c35d7c87f0dd1d676dac6c35f044015d` | 保留；用 DB last_seen_* 和浏览器请求核验 |
| 配置账号可实际登录 | `SecurityConfiguration`; `b4650abce5b87c7da8ffd89eb2a5aca9ac4ae688` | 随机账号/BCrypt 启动真实后端，不用 MockUser 代替端到端登录 |
| 单请求评估扇出上限 | `ToolCallBudget`; `34a69d790f189e159ef7f4841eb18b1fc99ac394` | 补恰好 50 / 第 51 / 失败计费回归，区分不同预算层级 |
| 原浏览器脚本与历史记录 | `6fd424a7ec775306020cb267d30b3d991f357775` | 加强原 `e2e/closed-loop.mjs`；旧“未报版本错误/有面板标题”不再当强断言 |
| 只读执行器与四工具、模型解析器及 HTTP 面 | `a0bd48d92da575ce78be61882d0ee1aebe4bfadb`, `b7f6db01decbd6afdbfe14195f2b555158a16f0c`, `ca3137b189167cac9ab35085bb95bfeeaf83f758` | 保留代码但默认关闭；不冒称已验收模型动态选择 |
| 模型单轮 20 秒截止 | `7510b26c8aea1ea5f815248f6d27b089c5a3c53f` | 保留；与下述连接资源问题不是同一件事 |

## 本次修复和验收地图

| 放行项 | 对应代码 / 回归 | 当前证据要求 |
| --- | --- | --- |
| 关闭展示路径自由模型叙述 | `AgentQueryService`, `AgentQueryServiceTest`, `DynamicAgentGateTest` | query/describe 恒确定性；旧 LLM 开关不能开启动态工具面 |
| 单岗位返回适用待确认项 | `AgentSessionService.rememberDescription`, `DecisionAgentController`, `DecisionAgentApiTest` | 原顺序不变；第二岗四项真实 UI/API 对齐 |
| 连续确认刷新剩余问题与版本 | `advanceProfileVersion`, `ProfileConfirmationController`, `AgentComposer` | 两次 RECORDED、USER_CONFIRMED、真实新版本评估；不能仅检查错误缺席 |
| 已提交但重算失败的可点击恢复 | `PendingConfirmations`, `ProfileConfirmationService` | 故障后 WRITTEN + 声明已在库；点击同请求补到 RECOMPUTED，版本不重复推进 |
| 新动作和同动作重试幂等键 | `PendingConfirmations` 及其回归 | 新回答新 UUID；网络未知/覆盖确认/补重算复用原 key 与日期 |
| 刷新恢复 | `agentSessionStorage`, `AgentComposer` | candidate 隔离、canonical 有序列表、原 session、剩余问题、指定岗位；GET 失败/过期不得伪恢复 |
| 清单失败、未刷新、不可判断、无变化 | `JobWatchlistService`, `WatchlistPanel`, `JobWatchlistApiTest` | UNAVAILABLE/EVALUATION_FAILED 与 NOT_REFRESHED/CALL_BUDGET_EXHAUSTED 分开；缺基线不算无变化 |
| 会话归属和并发 | `AgentSessionPorts`, JPA adapter, `AgentSessionPersistenceIntegrationTest` | 真实 PostgreSQL 6 项：首次写竞争、归属伪造、完整状态 CAS、过期 JPA 缓存、同版本待确认项竞争 |
| 版本冲突、未知、无可比结果 | `ProfileConfirmationApiTest`, `AgentSessionServiceTest`, `CandidateDecisionDiffServiceTest` | 拒绝结果不推进；未决值保留；unavailable 的计数为 null，不编零变化 |
| 50/51 预算 | `ToolCallBudgetTest`, `JobWatchlistServiceTest`, `CandidateDecisionDiffServiceTest`, `AgentExecutorTest` | 真正发起失败也占额度，超限未执行不能伪装已评估 |
| 真实决策读取丢应届条款 | `JpaDecisionStore.context`, `JpaDecisionStoreIntegrationTest`, `JpaDecisionStoreTest` | 首次 E2E 确实失败后修完整投影；真实 PG 先红后绿，新决策 v4 / 资格 v8 拒绝旧缓存，完整浏览器重跑通过 |
| 未核实条款的 false/false 被当无限制 | `EligibilityEvaluator.evaluateFreshGraduateStatus` | 原跨会话反例：EvidenceState 检查必须先于两个布尔字段的无限制推导，NOT_REQUIRED 与 UNKNOWN 分开 |
| 政治替代条件被当无限制 | `PoliticalRequirementClassifier` / `EligibilityEvaluator` | 原跨会话反例：“党员或民主党派”等复杂条件不能当无门槛，也不能简单判 NON_MEMBER 不可报；不够表达时待核原文 |

## 尚未关闭的账项（不能据此进入动态工具下一阶段）

1. **原连接资源问题未修复。** `PostgresFingerprintLock` 同时担任抽取 `FingerprintLock`
   与决策 `DecisionInputLock`，抽取模型往返仍占主池连接和外层事务。
   尝试 `177d311b276ef64edbfe94ea6d5460bde7deac9e` 已被
   `7697311742a24198258364d5a5c6148f39c5a10e` 回退。
   `docs/PHASE2_API.md` 保留原因；本次只纠正配置注释，未借重接旧 agent-app 或架构重构绕开。
2. **任务级内部评估/重试共享额度尚未实现/验证。** 原跨会话审计明确要求内部逐岗评估和重试
   也计入同一任务预算，并未规定模型轮数、顶层工具数、内部评估混为一个“50”计数。
   当前执行器共享的是每 run 的注册工具调用额度，
   watchlist/diff 各自每 HTTP 调用最多 50 次评估。一次 watchlist 工具调用可能扇出 50 次评估；
   多次工具调用可以再次获得内部额度。这不等于已有任务级下游共享额度。
   默认 maxSteps=8 也不能代替任务级内部评估/重试预算。动态工具保持关闭，留待专门核销。
3. **历史设计的模型校验失败持久化未实现。** 本次关闭自由叙述暴露路径，不能把此事说成已补
   review_item/审计失败表。将来开放模型前需重新确定校验失败持久化要求并验收。
4. **排序列表部分评估失败仍可能整体失败。** 旧 2026-09-13 审计已记录；关注清单逐项降级不
   代表排名路径同样支持部分成功。本次不把“清单失败展示修复”扩称所有查询故障已解决。
5. **原 1–3 项最小提问集/按影响和截止排序未核销。** 本次确保“适用问题可继续回答”，未实现
   原提案的最小集优化，更不使用未发生的解锁数量作承诺。
6. **模型自主动态选择未验收也未开启。** 可控 scripted planner、parser 单测及模型超时测试
   都不是模型根据真实工具结果选择下一步的证据。模型将来只能选只读查询和问题；规则产出结论，
   执行器管理权限和共享预算，业务修改仍需用户明确授权。不扩多 Agent、Forecast 或新架构。
7. **三项原始官方样本仍未验证。** 两份 Excel 在本机未找到；HTML09 实际哈希与历史 README
   不符，不能冒充原样本已验证。HTML10 与 PDF08 复用仓库既有文件并强断言历史 SHA-256，
   两项原用例本轮已实际执行通过。不能把旧机器五份原始样本执行记录当成这次全部执行。
8. **当前未截止查询不混入往年机会：本轮未专项验证。** 普通列表/第二岗的合成测试不覆盖
   自然语言时间条件与跨年度排除，不把“只有 2026 两岗的列表通过”当成历史隔离证据。
9. **声明不满足的适用时点与公告范围：本轮未专项核销。** 当前 scalar 声明的通用版本/幂等测试
   不等于验证了不同报名时点、不同公告范围之间的复用边界，保持待核。

## 隔离浏览器证据口径

`e2e/fixture.mjs` 仅允许带固定 marker 的新 PostgreSQL 容器、独立卷、数据库及合成候选人。
manifest 不含口令；凭据仅在启动进程内生成。新候选人/两个合成岗位的一次性 seed 拒绝已有数据。
故障注入只改该合成第二岗的 job_family，finally 恢复；不改候选人来凑初始条件。
每份快照保存真实确认账本、会话、岗位评估和关注基线，并检查迁移自带候选人的逐表 SHA-256。
哈希证明其数据未变，不声称证明没有任何只读访问。

主脚本从真实前端点击依次执行：列表 → 第二岗 → 连续确认与问题版本刷新 → 真实重算故障 →
点击恢复 → 刷新原会话后继续 → 空清单新增关注 → 展示版本基线 → 结论变化 → 刷新仍保留 →
确认屏幕展示版本。数据库断言是额外验证，不代替 UI 操作。

本次提交范围不包含克隆后发现的 `scripts/start-career-os.ps1` 非本轮删除状态；不恢复、不删除、不暂存该无关变更。

## 本轮执行结果（提交前的受控构建）

- Java 全模块 `verify`：**1025 项，1018 执行通过，0 失败，0 错误，7 跳过**。
  日志 `.run/reactor-verify-projection-20260914.log`；domain 201 / application 281 /
  infrastructure 418 / web 125。跳过为 4 项显式外网 opt-in 和上述 3 项原始样本；真实
  Testcontainers PostgreSQL 用例均执行，不把 Docker 不可用当作允许跳过。
- TypeScript 与前端：**14 个测试文件 / 97 项通过**，正式静态资源构建通过。
  日志 `.run/ui-final-stage1-20260914.log`。本地 Maven 跳过的是已独立完成的 Node 安装与 npm
  步骤，不是 Java 测试；原 CI 仍执行完整 `clean verify` 包括前端，不修改其跳过规则。
- 门禁：10 项 Python 门禁测试通过；未修改的实际报告门禁通过 1025 / 1018 / 7。
  Windows 本目录存在一个恰好 260 字符的报告路径，门禁调用使用 `\\?\` 长路径入口，
  不是删除该报告或放宽门禁；该报告的 3 项测试纳入总数。
- 真实浏览器修正后：**15 / 15 检查点通过**，运行时间
  `2026-09-14T01:56:43.826Z` 至 `2026-09-14T01:57:22.484Z`。
  run `20260914012911-fbbb54ec`，证据目录
  `.run/e2e-isolated/20260914012911-fbbb54ec/browser-evidence/`。
  JAR SHA-256：`b40cea6f13aa0f06a362a78077b97e0d8b371c473e2a41c41a6737911fc24b63`。
  这份 report 如实标记提交前 worktree；提交后需再以确切完整 SHA 绑定复跑及 CI，
  不能将 report 中当时的基线 HEAD 当成本轮修复 SHA。
- 首次失败 run `20260914003814-8fc6e280` 保留：它暴露了 JPA 投影丢字段，而非夹具缺规则。
  旧合成容器已精确停止，卷、日志未删。后续重跑使用新容器、新卷、新合成候选人，未重置旧候选人。

闭环已验证的正向结果包括两次 RECORDED、第二次 FEMALE 实际落库及对应资格规则 ELIGIBLE、
故障后的 WRITTEN 账本和本人声明保存、点击恢复到 RECOMPUTED 且无第二次版本写入、
剩余问题与原第二岗刷新恢复、本人声明仅到 CONDITIONAL、关注真实失败显示 UNAVAILABLE、
新动作新 key / 同动作重试原 key、变化刷新不消失，以及 ack 精确记录屏幕展示版本。
这些结果不覆盖上节仍未关闭的阶段门槛。
