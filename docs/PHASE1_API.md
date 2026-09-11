# Phase 1 API

Base path：`/api/v1`

## CRUD

| 资源 | 端点 |
|---|---|
| Organization | `GET/POST /organizations`、`GET/PUT/DELETE /organizations/{id}` |
| RecruitmentEvent | `GET/POST /recruitment-events`、`GET/PUT/DELETE /recruitment-events/{id}` |
| JobPosting | `GET/POST /jobs`、`GET/PUT/DELETE /jobs/{id}` |
| CandidateProfile | `GET/POST /candidates`、`GET/PUT/DELETE /candidates/{id}` |
| Eligibility | `POST /eligibility-assessments` |
| Opportunity | `GET /opportunities`、`PATCH /opportunities/{id}/status` |

资格评估请求：

```json
{"candidateId":"01992f09-0000-7000-8000-000000000001","jobId":"<job-uuid>"}
```

重复评估同一 Candidate/Job 会更新原 Assessment 和 Opportunity，不产生重复记录。

## Excel 增量导入

`POST /imports/excel`，内容类型为 `multipart/form-data`。

必需参数：

- `file`
- `announcementTitle`
- `sourceUrl`
- `recruitmentYear`

可选参数：`publishedOn`、`ageReferenceDate`、`defaultLocation`、`eventType`、`defaultOrganizationName`。

高校等单一招聘主体的附件可能只有“所在部门”而没有“招聘单位”列。此时调用方必须提供 `defaultOrganizationName`；系统不会把部门误建成单位，也不会从标题猜测单位名称。

导入器支持 `.xls`/`.xlsx`，自动识别常见中文列名。返回：

```json
{"inserted":2,"updated":1,"unchanged":3,"deactivated":1,"errors":[]}
```

差异语义：

- 稳定岗位键优先由 `公告 URL + 单位 + 官方岗位代码` 生成；没有代码才使用岗位名。
- 内容指纹覆盖岗位名、人数、学历、专业、年龄、经验、招聘对象、用工性质、职责和地点。
- 相同稳定键、相同指纹为 `unchanged`；指纹变化为 `updated`。
- 同一公告完整重导时，原有但本次缺失的岗位标记 `active=false`，计入 `deactivated`。
- 无法识别表头或存在行错误时禁止自动下线，防止坏文件污染历史状态。
- 未明确写明编制/用工性质时保存为 `UNKNOWN`，不根据招聘单位类型推断。
- “专业门类/专业类/相关专业”不会伪装成精确专业命中，资格结果进入 `UNCERTAIN`。

## 跨年度岗位族与关注清单

| 用途 | 端点 |
| --- | --- |
| 岗位族（跨年度出现记录） | `GET /api/v1/job-lineages` |
| 目标年度关注清单 | `GET /api/v1/watchlist?targetYear=2027` |

岗位族由 `job_posting` 与 `recruitment_event` 推导，不单独建表。归并键是
`单位名 + 技术族 + 岗位名称`（归一化后哈希），**刻意保守**：岗位名称实质改变就分成两族。
归并不足只会让信号偏弱，归并过度会凭空造出“连续招聘”的假趋势。

```json
{"signal":"RECURRING_ANNUAL","targetYear":2027,
 "observedYears":[2025,2026],"observationWindowStartYear":2025,"observationWindowEndYear":2026,
 "rationale":"观测窗口 2025–2026（2 年）内于 2025、2026 连续出现，2027 年再现的可能性值得关注"}
```

| 信号 | 含义 |
| --- | --- |
| `RECURRING_ANNUAL` | 观测窗口内逐年连续出现 |
| `INTERMITTENT` | 出现过多次但有断档 |
| `SINGLE_OCCURRENCE` | 窗口内只出现过一次 |
| `INSUFFICIENT_HISTORY` | 观测窗口不足 2 年，判断不了再现规律 |

**没有概率字段。** §11 把“没有历史样本支撑的精确上岸概率”列为非目标，因此只给可观测的
模式加依据。观测窗口取全部已落库数据的年度范围——只导入了一年数据时，所有族都是
`INSUFFICIENT_HISTORY`，而不会被读成“没有再现规律”。

两个端点都在默认拒绝范围内，需要认证。

再现信号目前**没有接入决策评分**。信号来源分支上的 `OpportunityScorer` 六维模型已被主干的
`AssessmentDimension` / `FitEvaluator` / `StabilityEvaluator` 取代，把 FUTURE 维度接进主干的
评估模型是一次独立的设计改动，不在本次移植范围内。

岗位族是每次请求从 `findAll()` 现算的投影，与 `GET /api/v1/jobs` 同一量级；数据量继续增长后
需要改成分页或物化。

## 每日变化摘要

`POST /api/v1/imports/excel` 的响应现在是 `{"result":…,"digest":…}`：导入结果之外附带当天摘要。

摘要只承载四类值得打扰人的事：

| 原因 | 触发条件 |
| --- | --- |
| `NEW` | 本次采集新增 |
| `UPDATED` | 内容指纹变化，硬条件需重新确认 |
| `DEACTIVATED` | 在最新的完整快照中消失 |
| `DEADLINE_APPROACHING` | 内容未变，但报名剩余天数在窗口内（默认 7 天） |

**未变化且截止日期还远的岗位不进 `entries`**，只体现为 `suppressedUnchangedCount` 计数，
用来说明“今天扫了多少条但没什么可说的”。每个岗位在一份摘要里最多出现一次；变化本身优先于
截止提醒——今天新增、后天截止的岗位报为 `NEW`，但仍带着剩余天数，不会因为分类而漏掉紧迫性。

```json
{"reportDate":"2026-09-11","suppressedUnchangedCount":23,"deadlineWindowDays":7,
 "entries":[{"reason":"NEW","title":"信息中心工作人员","organizationName":"杭州市儿童医院",
             "applicationEndsOn":"2026-09-15","daysUntilDeadline":4,
             "note":"本次采集新增岗位，报名还剩 4 天"}]}
```

报名已截止或截止日期未知时 `daysUntilDeadline` 为 null，不会出现“还剩 -3 天”这种提醒。

摘要条目**不带推荐等级**：主干把它放在候选人维度的 `DecisionAssessment.recommendationStatus`
上，而摘要报的是岗位变化，与具体候选人无关。要把两者合起来需要给摘要引入候选人参数，
属于独立的设计改动。

## 硬资格判定结果

`EligibilityAssessment.status` 与 `decision_assessment.eligibility_status` 的取值：

| 值 | 含义 |
| --- | --- |
| `ELIGIBLE` | 所有硬条件都有已确认事实且满足 |
| `CONDITIONAL` | 满足与否只取决于一件尚未完成的事，例如境外学历认证或预计毕业 |
| `NEEDS_CONFIRMATION` | 公告或个人字段不足，无法判定 |
| `CONFLICTING_EVIDENCE` | 该条所依赖的官方字段存在来源冲突，判定不可信 |
| `INELIGIBLE` | 至少一项硬条件明确不满足 |

**没有“大概可报”这一档。** 早期的 `LIKELY_ELIGIBLE` / `LIKELY_INELIGIBLE` 把“证据不足”
说成了一个带倾向的结论，V84 迁移已把它们连同 `UNCERTAIN` 一并并入 `NEEDS_CONFIRMATION`。
`LIKELY_INELIGIBLE` 没有并入 `INELIGIBLE`：基线要求后者是“明确不满足”，宁可多一条待确认，
也不静默丢掉一个其实可报的岗位。

逐条规则结果取最严重的一条作为整体结论，严重度为
`ELIGIBLE < CONDITIONAL < NEEDS_CONFIRMATION < CONFLICTING_EVIDENCE < INELIGIBLE`。
`CONDITIONAL` 排在 `NEEDS_CONFIRMATION` 之前，因为它已经知道缺什么、什么时候能补上；
`CONFLICTING_EVIDENCE` 排在其后，因为证据互相矛盾要先核对来源，比单纯缺证据更重。

只有 `ELIGIBLE` 能进入“建议报名”。`CONDITIONAL` 一律走复核——用户还没确认那件待完成的事，
系统不能替他认定它会发生。排除只看 `INELIGIBLE`：证据不足不是排除理由，是复核理由。

### CONDITIONAL 从哪来

学历达标之后还要看支撑它的那段学历是否已经落定：`EducationRecord.completionStatus`
为 `EXPECTED`，或 `credentialVerificationStatus` 为 `PLANNED` / `IN_PROGRESS`，都会得出
条件式结论并在说明里点出在等什么。只要另有一段已毕业且认证已完成（或无需认证）的学历
能单独满足要求，这个条件就不存在。没有逐段学历记录的旧资料按 `highestEducation` 判定，
不会因为没填明细被降级。

### CONFLICTING_EVIDENCE 从哪来

`OfficialJobAdmissionService` 发现官方来源在某字段上互相矛盾时，除了把质量标成
`REVIEW_REQUIRED`，还会记一条 `CONFLICTING_FIELD_EVIDENCE` 原因码。决策评估会把冲突字段集
传给资格评估器，依赖这些字段的规则（`educationRequirementText`、`majorRequirementText`、
`ageRequirementText`）不产出判定——手里的岗位要求本身就不可信，此时说“满足”或“不满足”
都是在替官方做决定。

### 逐条硬规则

| RuleType | 判定依据 | 缺数据时 |
| --- | --- | --- |
| `AGE` | 岗位年龄上限 + 基准日 vs 出生日期 | 待确认；出生日期只精确到月且处于边界时同样待确认 |
| `EDUCATION` | 岗位最低学历 vs 最高学历，再看支撑学历是否落定 | 待确认 / 条件可报 |
| `EXACT_MAJOR` | 岗位专业目录 vs 候选人专业 | 门类或宽泛目录一律待确认——基线要求专业等同性由招录单位确认 |
| `GRADUATE_YEAR` | 岗位应届届别 vs 毕业年份 | 待确认 |
| `EXPERIENCE` | 岗位最低年限 vs 逐段核验的全职经历 | 待确认；明确写“不限”按 0 年处理 |
| `PROFESSIONAL_TITLE` | 岗位职称要求 vs 已确认职称 | 待确认 |
| `POLITICAL_AFFILIATION` | 公告明确写死的党员要求 vs 候选人政治面貌 | 待确认 |
| `GENDER` | 公告性别限定 vs 候选人性别 | 待确认 |

`POLITICAL_AFFILIATION` 只认写死的硬性要求。“中共党员优先”“党员或民主党派”“不限”都是偏好
或无限制，不构成门槛——把偏好当门槛会凭空滤掉可报的岗位。预备党员单列为待确认：多数公告写
“中共党员（含预备党员）”，也有只要正式党员的，公告没说清楚时不替它决定。

`GENDER` 读的是公告已经公开写明的限定，用途只有一个：让候选人不必在报不了的岗位上花时间。
“不限”和空值都不构成限制；写了限定但两性都提到（或都没提到）时无法解析，落到待确认。

**尚未实现的规则**：留服认证适用性、社保与未落实工作单位、户籍。这三条的候选人侧字段
（户籍、报名时社保与档案状态）目前不在 `CandidateProfile` 上，需要先加字段、迁移和资料页
录入，才谈得上判定。在此之前它们不会产生任何结论，也不会被当成“已满足”——公告里的这类
条件会留在 `otherRequirements` 原文里等人工复核。
