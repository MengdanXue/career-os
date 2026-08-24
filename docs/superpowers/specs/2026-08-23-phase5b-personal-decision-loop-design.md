# Phase 5B：个人职业决策闭环设计

**状态：** 对话设计已确认，等待书面规格复核  
**日期：** 2026-08-23  
**首位候选人：** 测试候选人  
**目标年度：** 2027，延伸至 2028 第二个完整春季周期

## 1. 目标

Phase 5B 将现有 Career OS 从“证据可靠的招聘数据工作台”推进为“普通用户每天能够执行的个人职业决策系统”。本阶段不重建采集、证据、资格和路线计算底座，而是在现有能力上打通以下闭环：

```text
看到今天最重要的事项
  → 补齐影响资格的个人证据
  → 系统自动重新计算
  → 查看具体可报、待确认和不可报岗位
  → 关注未来岗位族或跟踪当前申请
  → 持续收到截止、变化和准备提醒
```

首要原则是让系统为用户整理工作，而不是把原始采集、解析失败和大批人工审核任务转交给用户。

## 2. 已有能力与复用边界

本设计复用：

- `CandidateProfile`、`CandidateFacts` 与字段指纹确认机制；
- 官方公告、附件、招聘事件、岗位和单位证据；
- 确定性资格、匹配、稳定性和决策快照；
- Phase 5A 的三种学历场景、四条路线、年龄窗口和历史节奏；
- `Opportunity` / Application 状态能力；
- 增量采集、内容指纹、Review Queue 和来源覆盖账本；
- `2026-08-22-historical-opportunity-forecast-design.md` 中的历史复招与预测方法。

本阶段不引入微服务、工作流引擎、消息队列、Elasticsearch、自动投递或新的硬资格 LLM 决策。

## 3. 现状问题

### 3.1 用户角色错位

当前首页突出数百条人工复核和数据更新任务，更像数据运营后台。普通用户真正关心的是新机会、截止日期、资格缺口和准备任务。

### 3.2 决策口径断裂

历史规划、官网 Excel 初筛、Opportunity Admission 和 T1/T2/T3 可信机会使用不同入口。用户会同时看到大量原始记录、若干画像匹配和零可信机会，却无法知道这些数量之间的关系。

### 3.3 规划无法下钻执行

Phase 5A 能输出路线和资格数量，但“可报 3 个”“待确认 34 个”不能进入对应岗位全集，也不能把结果转为关注、材料或申请行动。

### 3.4 画像存在值与证据状态冲突

当前数据库可能同时保存“7 年经验”和空工作经历、政治面貌值与 `UNKNOWN` 确认状态、空技能与“已确认”等组合。资格引擎虽会保守处理，但普通用户无法理解或修复这些冲突。

### 3.5 历史与当前状态混淆

历史岗位适合研究未来重复机会，当前岗位才可进入报名流程。两者必须共享证据视图但不能共享“仍可报名”的语义。

## 4. 产品方案

采用“统一个人决策读模型 + 确定性今日行动 + 最小申请跟踪”方案。保留现有领域事实和规则服务，不通过前端拼接复制业务规则。

拒绝两种替代方案：

- 只修改页面和文案：不能消除数量、状态和重新计算口径冲突；
- 重建通用工作流平台：复杂度高于当前单用户、单区域场景的需要。

## 5. 信息架构

主导航调整为：

1. `今天`：最多三项个人行动；
2. `我的规划`：路线、场景、年龄和历史规律；
3. `岗位`：历史机会与当前机会两个明确标签页；
4. `我的申请`：关注、材料、报名和考试状态；
5. `我的资料`：证据化候选人画像。

`更新岗位库`、来源运行和 Review Queue 移入二级 `数据管理`。它们继续可访问，但不再占用普通用户的核心注意路径。

## 6. 核心需求

### P5B-ACT-001：今日行动数量与来源

首页必须展示零至三项 `PersonalAction`，按以下优先级生成：

1. 十四天内截止且资格不为明确不符合的当前岗位；
2. 影响至少一个目标岗位资格的候选人证据缺口；
3. 新增、关键字段变更或取消的目标岗位；
4. 已进入申请流程的下一步动作；
5. 当前时间线中的笔试、材料或认证准备任务。

每项行动包含：标题、原因、影响对象数量、官方或系统截止时间、证据强度、一个站内深链接。不得把原始 Review Queue 数量直接转换为用户行动。

### P5B-ACT-002：今日行动的确定性

`PersonalActionService` 根据数据库状态生成行动。LLM 可以润色解释，但不能改变优先级、截止日期、资格状态或深链接。相同快照与 `asOf` 必须生成相同结果。

### P5B-PLAN-001：规划数量下钻

路线卡中每个场景的 `ELIGIBLE`、`CONDITIONALLY_ELIGIBLE`、`UNCERTAIN` 和 `INELIGIBLE` 数量都必须可点击，并进入带以下固定筛选条件的历史岗位列表：

- candidate ID；
- target year；
- route code；
- scenario code；
- qualification outcome；
- planning algorithm version。

列表数量必须等于路线卡数量。若读取快照版本已变化，页面明确提示重新加载，不能混合两个版本的数量和明细。

### P5B-PLAN-002：路线并列与月份结论

- 路线指数差距不超过两分时显示“并列主攻”，不使用虚假精度强制选择唯一第一名；
- 首页关注月份使用“样本门控通过的历史高峰”或“当前日期之后的下一个有效窗口”；
- 不得简单取排序后的第一个月份；
- 预测月份必须显示独立招聘事件数与历史年份，不得使用岗位行数代替事件数。

### P5B-JOB-001：历史机会与当前机会

统一岗位研究接口必须返回 `researchContext`：

- `HISTORICAL`：已截止，仅用于资格模拟、重复规律和准备研究；
- `ACTIVE`：公告仍在有效生命周期内，可进入关注或报名流程；
- `UPCOMING_SIGNAL`：没有新公告，仅有历史复招观察信号；
- `INACTIVE`：取消、下线或已截止。

前端必须始终显示该状态。历史岗位不能使用“立即报名”“待报名”等当前行动文案。

### P5B-JOB-002：个人岗位研究列表

岗位研究列表支持：

- 历史/当前上下文；
- 路线与岗位族；
- 资格状态；
- 年龄上限；
- 学历；
- 用工性质；
- 地点、单位和标题；
- 是否重复出现；
- 官方证据完整度。

每条记录显示硬阻断、待确认原因、官方来源和计算版本。默认排除博士限定、纯教师、纯行政、纯销售、劳务派遣、短期项目制及其他用户配置排除项。

### P5B-JOB-003：历史关注对象

历史岗位不能创建报名记录，只能创建 `WatchTarget`：

- 规范单位；
- 岗位族；
- 可选地点；
- 历史依据岗位；
- 关注原因；
- 最近出现年份；
- 建议关注月份；
- 状态 `WATCHING` 或 `DISMISSED`。

新公告与 WatchTarget 匹配时，系统重新执行当前画像资格判断，并生成新增机会行动。

### P5B-APP-001：当前申请状态

当前有效岗位可进入以下最小状态流，允许跳过中间状态：

```text
WATCHING
→ PREPARING_MATERIALS
→ READY_TO_APPLY
→ APPLIED
→ WRITTEN_EXAM
→ INTERVIEW
→ MEDICAL_OR_PUBLICITY
→ OFFERED | REJECTED | WITHDRAWN | CLOSED
```

每个申请记录保存 candidate、job、当前状态、下一步动作、用户截止时间、官方截止时间、材料缺口和状态历史。系统不自动提交报名。

### P5B-PROFILE-001：证据化画像任务

`CandidateEvidenceTask` 从候选人事实与目标岗位影响中派生，至少覆盖：

- 工作经历起止日期与证明类型；
- 政治面貌确认；
- 硕士预计毕业月与实际学位取得日；
- 留服状态、完成日和正式专业名称；
- 职称证据；
- 可核验技能、项目和研究材料。

任务显示它影响的岗位数和主要规则类型。空值、未知值和明确不存在必须分别表达。

### P5B-PROFILE-002：工作年限唯一口径

资格与优势计算只能读取已核验 `CandidateEmploymentRecord` 合并后的完整年限。手工总年数不得作为硬资格证据。兼容字段 `experienceYears` 只能作为迁移提示或展示旧值，不能参与新计算。

### P5B-PROFILE-003：事实变更差异

保存并确认候选人事实后，系统以同一岗位集合、同一 `asOf` 和新旧 profile version 计算 `DecisionChangeSummary`：

- 新增可报数量；
- 减少待确认数量；
- 新增不可报数量；
- 受影响岗位；
- 变化原因。

如果旧快照不可用，系统明确说明只能展示新结果，不伪造差异。

实现状态（2026-08-24）：Slice 2 已落地。画像页先保存并确认事实，再用旧 `profileVersion` 所覆盖的岗位 ID 和固定 `asOf` 生成差异；历史缺失时三个计数为 `null`。资格工作年限只接受官方报名截止日，计算时间与资格截止日已拆分，截止日也进入决策输入身份。

### P5B-COV-001：覆盖范围语义

覆盖页面和规划摘要必须区分：

- `configuredCoverage`：已配置来源 × 年度的扫描完成度；
- `targetMarketCoverage`：产品目标来源目录中的接入、部分、失败和未接入状态；
- `analysisCoverage`：当前结论实际使用的来源、年度和岗位数量。

“已完成”只能描述配置来源，不能写成“浙江市场数据完整”。零样本路线显示“未覆盖/样本不足”，不参与实质排名。

## 7. 领域与读模型

### 7.1 PersonalAction

```text
id
candidateId
type
priority
title
reason
impactCount
dueAt
evidenceStrength
deepLink
sourceSnapshot
asOf
```

行动主体是可重建读模型。只有用户完成、忽略或延后状态需要持久化，避免数据库保存大量会因资格重算而过期的任务副本。

### 7.2 CandidateJobResearchItem

统一历史与当前岗位的只读投影：

```text
job identity
researchContext
route
scenario
qualification outcome
blocking reasons
uncertainties
employment identity
source/evidence coverage
deadline
recurrence signal
decision/profile/content versions
```

它调用现有资格和证据服务，不复制规则。

### 7.3 WatchTarget

持久化候选人与“单位 × 岗位族”之间的关注关系。WatchTarget 不是岗位副本，不保存可被官方事实替代的岗位条件。

### 7.4 Application

复用并扩展现有 Opportunity/Application 聚合。状态迁移记录审计时间与用户备注，当前状态与历史状态必须在同一事务中写入。

### 7.5 DecisionChangeSummary

只读差异结果，通过旧、新候选人 profile version 与固定岗位快照计算。它不修改任何历史决策快照。

## 8. 应用服务边界

- `PersonalActionService`：汇总资格、申请、变化和时间线，输出最多三项行动；
- `CandidateJobResearchQuery`：提供规划数量下钻和统一岗位研究列表；
- `CandidateEvidenceTaskService`：计算画像缺口及影响范围；
- `CandidateDecisionDiffService`：计算画像变更前后差异；
- `WatchTargetService`：维护历史关注并匹配新公告；
- `ApplicationTrackingService`：维护申请状态与下一步动作；
- `CoverageScopeQuery`：输出三层覆盖语义。

这些服务通过现有 application ports 访问资格、岗位、证据、变化和持久化能力。Controller 只做请求校验与 DTO 映射。

## 9. API 设计

```http
GET /api/v1/candidates/{candidateId}/personal-actions?asOf=YYYY-MM-DD

GET /api/v1/candidates/{candidateId}/job-research
    ?context=HISTORICAL
    &route=PUBLIC_TECH
    &scenario=PRE_GRADUATION
    &outcome=ELIGIBLE
    &targetYear=2027
    &page=0&size=20

GET /api/v1/candidates/{candidateId}/evidence-tasks

GET /api/v1/candidates/{candidateId}/decision-change-summaries/{profileVersion}

GET  /api/v1/candidates/{candidateId}/watch-targets
POST /api/v1/candidates/{candidateId}/watch-targets
PATCH /api/v1/candidates/{candidateId}/watch-targets/{watchTargetId}

GET  /api/v1/candidates/{candidateId}/applications
POST /api/v1/candidates/{candidateId}/applications
PATCH /api/v1/candidates/{candidateId}/applications/{applicationId}

GET /api/v1/coverage-scope
```

列表响应包含总数、当前页、固定筛选、`asOf`、profile version、job content version 和算法版本。变更命令使用乐观锁；版本冲突返回 RFC 9457 Problem Detail，不覆盖较新的用户状态。

## 10. 页面行为

### 10.1 今天

- 首屏是最多三张行动卡；
- 没有行动时显示“今天没有新的资格、截止或准备事项”；
- 数据源失败只在影响个人结论时出现一条合并警告；
- 数据管理入口放在页面尾部的次要区域。

### 10.2 我的规划

- 路线卡数量变为链接；
- 相差不超过两分的前两条有证据路线显示“并列主攻”；
- 零样本路线不显示数字 0 的竞争性排名；
- 风险项链接到对应画像字段或证据任务；
- 代表岗位保留，作为快速样本而非唯一明细入口。

### 10.3 岗位

- 顶层使用“当前机会 / 历史机会”两个标签；
- 当前机会默认只显示资格非 `INELIGIBLE` 且未失效的目标岗位；
- 历史机会允许查看所有资格状态并创建 WatchTarget；
- 每条记录先显示资格，再显示软匹配和稳定性；
- 来源、附件和原始条件可从站内详情下钻。

### 10.4 我的申请

- 按下一步动作和截止时间排序；
- 状态变化可撤销最近一次用户操作，但不删除审计历史；
- 材料缺口链接到画像证据；
- 历史岗位不能出现在申请列表。

### 10.5 我的资料

- 页面顶部显示“最影响资格的缺口”；
- 经验总年数由已核验经历自动计算；
- 每个事实显示值、确认状态和影响；
- 保存后展示重算状态，完成后展示 DecisionChangeSummary；
- 重算失败时保留已成功保存的事实并提供重试，不把旧结论标为当前结论。

## 11. 一致性、失败与降级

- 任何聚合区段读取失败都返回 `available=false`，不得把空值解释为零；
- 历史、当前和预测上下文不可通过前端文案推断，必须由后端枚举提供；
- 官方截止时间缺失时 `dueAt` 为空，不能从历史日期生成当前提醒；
- 候选人修改、事实确认和 profile version 更新保持现有事务语义；
- 资格重算异步或超时时，UI 显示“资料已保存，结论正在更新”，旧快照带明确过期标记；
- WatchTarget 匹配失败不影响公告入库；失败记录可重试并出现在数据管理；
- 当前岗位内容指纹变化后，旧资格与申请材料检查标记为需复核；
- 明确排除规则命中时保留岗位事实，但不进入个人优先列表。

## 12. 数据迁移

迁移遵循“不覆盖用户编辑值”：

- 为新表创建独立 Flyway migration；
- 不修改已发布 migration；
- 旧 `experienceYears` 不自动生成工作经历；
- 值存在但确认状态为 UNKNOWN 时保持 UNKNOWN，并创建证据任务；
- 空技能或研究关键词只有在用户明确确认为“没有”时才是已知空值；历史 Seed 的错误空确认通过精确指纹和 Seed profile version 条件修复；
- 已有 Opportunity/Application 数据映射到最接近的新状态，无法确定时保持 `WATCHING` 并记录迁移来源。

## 13. 测试策略

所有实现采用红—绿—重构。

### 13.1 领域与应用测试

- PersonalAction 优先级、最多三项和确定性；
- 资料缺口影响数量与明确空/未知区分；
- 工作经历合并、完整年计算和旧总年数不参与资格；
- 规划聚合数与下钻列表数一致；
- 历史/当前/预测上下文隔离；
- 路线并列与历史月份高峰选择；
- 新旧 profile version 的资格差异；
- WatchTarget 新公告匹配；
- Application 合法跳转、跳过和审计；
- 默认排除项不会进入个人优先列表。

### 13.2 持久化与迁移测试

- 全新数据库应用所有 migration；
- 从 V23 升级保留候选人、岗位、证据和申请数据；
- 用户编辑画像不被 Seed 修复覆盖；
- 乐观锁冲突不丢失状态；
- 同一 WatchTarget 不重复创建。

### 13.3 API 测试

- 固定筛选、分页、版本元数据和 Problem Detail；
- 部分子系统失败时保留可用区段；
- 历史岗位创建申请返回业务错误；
- 过期岗位不能进入 `READY_TO_APPLY`；
- 未确认事实不会通过 API 被呈现为满足。

### 13.4 前端与浏览器验收

- 首页零至三项行动及空状态；
- 从画像缺口进入字段、确认、重算并查看变化；
- 从路线数量进入明细且数量一致；
- 当前与历史标签、动作和文案不混淆；
- 历史岗位创建关注，当前岗位创建申请；
- 截止提醒与官方日期一致；
- 后台 Review Queue 不出现在普通首页；
- 1440 px 与 390 px 无水平溢出；
- 控制台无错误，关键请求无 5xx。

## 14. 验收标准

1. 首页最多展示三项个人行动，不展示原始 Review Queue 数量作为用户任务。
2. 规划页每个资格数量均可下钻，结果总数与卡片数字一致。
3. 历史岗位、当前岗位、未来观察信号和失效岗位由后端状态明确区分。
4. 博士限定、纯教师、纯行政、销售、派遣和项目制岗位不进入个人优先列表。
5. 补充并确认一段工作经历后自动重算，显示可报、待确认和不可报变化。
6. 用户可以把历史岗位转为单位/岗位族关注，把当前岗位加入申请流程。
7. 截止提醒只使用官方明确日期，历史日期不会伪装成未来截止日期。
8. 经验、技能、政治面貌和学历认证不存在值与确认状态冲突。
9. 路线指数接近时显示并列主攻；关注月份使用有效历史高峰或下一个窗口。
10. 覆盖页面明确区分配置来源完成度、目标市场覆盖和分析样本。
11. Java、前端、迁移、API 与真实浏览器闭环测试全部通过。
12. 现有采集、证据、资格、规划和站内岗位详情能力保持兼容。

## 15. 非目标

本阶段不做：

- 自动报名、自动投递或向外部机构发送信息；
- 全国招聘扩张；
- 新的复杂预测模型或录取概率；
- 多用户权限和云端敏感证件管理；
- 语音面试、完整简历生成器；
- 通用工作流引擎、复杂多 Agent 自治；
- 用 LLM 覆盖硬资格、截止日期或用工身份结论。

## 16. 交付切片

Phase 5B 分为四个可独立验收的纵向切片：

1. **事实与行动：** 清理画像冲突，EvidenceTask、PersonalAction 和新版 Today；
2. **规划下钻：** CandidateJobResearchQuery、路线数量链接和历史机会列表；
3. **关注与申请：** WatchTarget、Application 状态、当前/历史动作隔离；
4. **一致性收口：** 覆盖语义、路线并列、月份算法、数据管理降级、浏览器验收。

每个切片在进入下一个切片前必须通过本切片的领域、持久化、API 和前端测试。来源扩张、历史预测增强、材料/简历/面试策略作为后续独立规格实施，不阻塞 Phase 5B 个人决策闭环。
