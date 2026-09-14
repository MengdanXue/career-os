# 第一阶段边界核销记录（固定 SHA）

日期：2026-09-14（Asia/Shanghai）。范围仍限第一阶段；动态工具入口保持关闭，不合并、不部署、不修改原用户数据。现有 `scripts/start-career-os.ps1` 删除仍是工作区外的既有未提交变更，本轮没有恢复、暂存或处理。

## 代码、分支与证据基线

- 功能代码已验收 SHA：`c0054c4e6e71c6e3f9048e672ccb256c0c88f539`，标题 `fix(stage1): close deterministic confirmation and watchlist recovery gaps`。
- 已有交付记录 SHA：`525b5a134731e46b16ec4eac59a409366cd02b5c`，标题 `docs(stage1): record delivery evidence for c0054c4`；两者均保留。
- 本轮分支：`codex/stage1-release-gates-20260914`。本轮新增只应是本记录及其 CI 追记，不改变上述功能代码。
- 现状：工作树仅有既有 ` D scripts/start-career-os.ps1`；无暂存差异。Maven、Git、应用、测试和浏览器进程在收尾检查时均已结束。

## A1—A12 核销口径

仓库和现有审计文件没有字面 `A1`—`A12` 编号。为避免伪造历史编号，下面按上一轮原放行清单的十二个功能包做保守映射；编号只是索引，不替代原始清单。`功能证据通过；固定 SHA 浏览器待复验` 不等于整条已放行。

| 原 A 项（本记录映射） | 直接关联实现与证据 | 状态 | 仍缺什么 |
| --- | --- | --- | --- |
| A1 展示路径关闭自由模型叙述 | `AgentQueryService`、`DynamicAgentGateTest`、Java/UI 回归；旧浏览器 15/15 | 功能证据通过；固定 SHA 浏览器待复验 | 旧浏览器报告的 `codeSha` 仍是 `261e389…` 且带工作树差异，不能冒充 `c0054…` |
| A2 单岗位追问返回适用待确认项 | `AgentSessionService`、`DecisionAgentApiTest`；浏览器第二岗位四项问题 | 同上 | 同上 |
| A3 连续确认刷新剩余问题与版本 | `advanceProfileVersion`、确认 API/UI；浏览器两次 `RECORDED` 与刷新状态 | 同上 | 同上 |
| A4 写入后重算失败可点击恢复 | `ProfileConfirmationService`、`PendingConfirmations`；Java 与浏览器故障注入/同钥匙恢复 | 同上 | 同上 |
| A5 新动作与重试的幂等键 | UI/API/服务测试；重复请求不二次写入 | 同上 | 同上 |
| A6 会话刷新恢复 | `agentSessionStorage`、GET/CAS 测试；浏览器保留原 session、岗位顺序和剩余问题 | 同上 | 同上 |
| A7 关注清单失败/未刷新/不可判断/无变化分流 | `JobWatchlistService`、`JobWatchlistApiTest`、UI 测试；浏览器真实失败显示 | 同上 | 同上 |
| A8 会话归属与并发 | `AgentSessionPersistenceIntegrationTest` 等真实 PostgreSQL 回归（上一轮 7-skip 运行） | 已核销（真实 PG 证据） | 当前 Docker 不可用，不能把本次环境重跑误写成新的 PG 证据 |
| A9 版本冲突、未知、无可比结果 | `ProfileConfirmationApiTest`、`AgentSessionServiceTest`、`CandidateDecisionDiffServiceTest` | 已核销（单测/API；未提供浏览器强制要求） | 仍须遵守不可把 `unavailable` 计为零变化 |
| A10 50/51 单请求预算 | `ToolCallBudgetTest`、watchlist/diff/agent executor 回归 | 已核销（单测；仅单请求层） | 任务级内部评估/重试共享预算仍是动态入口硬门槛 |
| A11 决策读取保留招聘事件应届条款 | `JpaDecisionStore` 与真实 PG projection 回归；旧浏览器使用新资格版本 | 功能证据通过；固定 SHA 浏览器待复验 | 同 A1；不能用旧 dirty-worktree 报告证明固定 SHA |
| A12 保守资格分支（未核实 false/false + 政治替代条件） | `EligibilityEvaluator`、`PoliticalRequirementClassifier` 领域反例与回归 | 已核销（单测证据） | 没有新的全公告真实链路；保持待核而非把单测当全量数据验收 |

### 已核实的运行数字与跳过项

- 上一轮与 `c0054…` 代码树对应的最终 Java 记录：`1025` 项、`1018` 执行、`0` 失败、`0` 错误、`7` 跳过，见 `.run/reactor-verify-projection-20260914.log`；跳过门禁见 `.run/skip-gate-final-20260914.log`。
- 7 个跳过原因及放行条件：四个官方 live smoke（Government SOE、Tonglu、UCAS Hangzhou、Westlake）因未设置 `CAREER_OS_LIVE_SMOKE`，须在允许官方网络的环境显式设置后重跑；`06-hdu-2026-second-plan.xlsx` 缺失，需提供该固定样本；`03-hz-unified-2025-plan.xls` 缺失，需提供该固定样本；`09-hz-capital-recruiting.html` 缺失且历史记录指出当前 hash 与 README 不同，需提供并核对批准的固定样本/hash。
- 当前分支重新执行的 `verify` 构建见 `.run/reactor-verify-boundary-audit-20260914.log`，最终 `BUILD SUCCESS`、UI `14` 文件/`97` 测试通过；但本次 Docker API 不可用，基础设施/网页测试出现环境性跳过（不是上一轮 7 项证据），不替代上面的 7-skip 记录。

## 本轮三个新增边界：隔离反例结果

测试源只存在于固定 `c0054…` 的独立 detached worktree `career-os-stage1-boundary-audit-c0054`，没有进入功能分支；原始日志保存在 `.run/stage1-boundary-counterexample-20260914.log`。3 个测试均失败，恰好证明当前实现不能关闭这三个边界，未通过删测试、加 skip 或降低断言来“修绿”。

1. **当前未截止查询混入历史岗位（失败）**：构造一个 `applicationEndsOn=2026-08-31`、仍为 `active` 的岗位和一个 `2026-12-31` 岗位；`DecisionRankingService.rank` 返回两者。实现只调用 `findActive()`，没有按 `now` 的报名截止日过滤。
   - 最小修法提议：在“当前”查询契约中按 `applicationEndsOn >= asOf` 过滤，并先明确 `null` 截止日是排除还是显式不完整结果。影响当前总数、分页和排序，不在本轮自行决定资格语义。
2. **本人声明没有公告/适用时点作用域（失败）**：`ConfirmationRequest` 与 `LedgerEntry` 的 record components 均没有 `jobPostingId`/公告标识或 `applicableAsOf`，同一 scalar 画像值无法证明来自哪个公告、适用于哪个报名截止日。
   - 最小修法提议：为确认请求、台账键和持久化约束增加公告/岗位范围及适用日期，并定义跨公告复用、旧台账回填和幂等重放规则。该变更会影响资格规则含义、跨公告声明复用、数据库迁移和事务边界，需单独批准，不能在本轮补丁式加入字段。
3. **排序评估失败没有诚实可恢复结果（失败）**：让第二个岗位 assessor 抛出 `assessment unavailable`；`DecisionRankingService.rank` 直接传播异常，整页失败，不保留成功岗位，也没有不完整标志或恢复入口。
   - 最小修法提议：逐岗位捕获可恢复评估错误，返回带原因/重试信息的 `INCOMPLETE`/`UNAVAILABLE` 行并保留成功项；同时明确 `total`、排序和 HTTP/API schema。涉及验收标准与产品结果语义，不在本轮擅自改动。

## 门槛分栏

### 当前闭环门槛

- 三个新增反例仍未关闭：当前未截止查询的历史混入、本人声明的公告/适用时点绑定、排序失败的诚实可恢复结果。
- 15 项浏览器记录功能上为 `15/15`，但报告字段是 `codeSha=261e389…` 加 53 项工作树差异，JAR SHA 为 `b40cea6f…`；不能证明是在固定 `c0054…` 干净副本运行。已创建固定 SHA detached worktree，并尝试 `node e2e/fixture.mjs create`；Docker API 不可用，日志见 `.run/clean-c0054-e2e-create-20260914.log`。因此固定 SHA 的真实浏览器门槛保持未关闭。
- 7 项跳过仍按上表原因和条件保留，不能改写为 PASS。

### 动态入口开放门槛

- 动态模型选择保持关闭。
- 任务级内部评估/重试共享预算尚未实现或验证；已有的是每次工具调用/HTTP 的局部 50 次限制，不是跨任务共享额度。该项继续登记为硬门槛。
- 只有在上述共享预算、权限边界、规则结论与只读问题/查询范围完成真实验收后，才重新评估开放入口；本轮不做预算重构、真实模型验收、抽取锁重构或最小提问集优化。

### 其他历史遗留

- `PostgresFingerprintLock` 同时承担抽取与决策锁，模型往返仍在事务/连接池边界内；此前尝试已回退，本轮不重做。
- 历史模型校验失败的 `review_item` 持久化尚未实现。
- 原 1—3 项最小提问集/按影响与截止日排序未核销。
- 三项官方固定样本缺失/哈希不一致，见跳过项；HTML10/PDF08 的历史 SHA 用例仅代表已有样本已执行。

## 浏览器 JAR 与代码 SHA 对应结论

旧报告的 JAR 指纹 `b40cea6f13aa0f06a362a78077b97e0d8b371c473e2a41c41a6737911fc24b63` 与当前重新打包产物不相同（本次构建产物 SHA256：`605d3af…`，构建自当前 `525b5…` 工作树）；报告本身又明确记录基线 `261e389…` 和未提交差异。故只能复用其业务步骤作为历史功能证据，不能复用为本轮固定 SHA 的浏览器验收。固定 SHA 复验因 Docker daemon 不可用而受阻，未把旧 15/15 写成新 SHA 绿灯。

## 交付边界

本轮不修改产品代码，不修改原用户数据，不操作 Fabric/8010，不合并 main，不部署。若后续要关闭三个反例，应分别先确认截止日过滤的空值语义、声明跨公告的资格含义/迁移与事务设计、以及排序不完整结果的 API 验收标准，再做最小修法与新的真实链路验收。
