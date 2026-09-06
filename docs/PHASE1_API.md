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

响应同时返回 `assessment`（§6.1 硬判定）、`opportunity` 和 `tier`（§3 分池）。两者互相独立：
资格结论不因所在池而改变，分池也不因资格而改变。

```json
{"tier":{"tier":"T1_ESTABLISHMENT_TARGET","employmentType":"ESTABLISHMENT",
         "organizationType":"HOSPITAL","evidenceSufficient":true,
         "reason":"用工身份明确为事业编制且单位类型一致，进入 T1 主攻池"}}
```

分池规则：

- `T1_ESTABLISHMENT_TARGET`：公告明确事业编制，且单位类型与之一致。
- `T2_IDENTITY_REVIEW`：公共机构的非事业编岗位，或用工身份缺失／与单位类型矛盾，需逐岗人工核验。
- `T3_STABLE_SOE_BACKUP`：国有企业岗位。可作稳定备选，但**不得标成事业编**。
- `EXCLUDED`：劳务派遣、项目聘用，以及非公共机构的合同制／编外岗位。
- `UNKNOWN`：单位性质与用工身份都不足以进入任一池。

用工身份缺失时不会被提升为 T1；`evidenceSufficient=false` 表示该结论只是待核验初判。

`EmploymentType` 覆盖 §5 要求区分的七类身份：

| 取值 | 含义 | 默认落池 |
| --- | --- | --- |
| `ESTABLISHMENT` | 事业编制 | T1（单位类型一致时） |
| `AUTHORIZED_HEADCOUNT` | 员额／报备员额 | T2 |
| `SCHOOL_HIRED` | 校聘 | T2 |
| `STATE_OWNED_REGULAR` | 国企正式 | T3（单位为国企时） |
| `PERSONNEL_AGENCY` | 人事代理 | 公共机构 T2，否则排除 |
| `CONTRACT` | 合同制／编外 | 公共机构 T2，否则排除 |
| `LABOR_DISPATCH` | 劳务派遣 | 排除 |
| `PROJECT_BASED` | 项目聘用 | 排除 |
| `UNKNOWN` | 未知 | 按单位性质降级，绝不进 T1 |

### 逐字段证据

`jobPosting.fieldEvidence` 保留抽取阶段每个 `ExtractedFact` 记下的证据片段 ID，按字段索引：

```json
{"fieldEvidence":{"MINIMUM_EDUCATION":["<fragment-uuid>"],"MAXIMUM_AGE":["<fragment-uuid>"]}}
```

资格判定的每条 `ruleResults[*].evidenceIds` 由此指向公告里的**具体片段**而非整份公告。
没有片段级证据的字段（例如 Excel 导入的行、或抽取时为 UNKNOWN 的字段）回落到公告级
`evidenceIds`，不会变成"无证据"。

| 规则 | 取证字段 |
| --- | --- |
| `AGE` | `MAXIMUM_AGE` |
| `EDUCATION` | `MINIMUM_EDUCATION` |
| `EXACT_MAJOR` | `MAJOR_TEXT` |
| `GRADUATE_YEAR` | `ACCEPTED_GRADUATION_YEARS` |
| `EXPERIENCE` | `MINIMUM_EXPERIENCE_YEARS` |
| `PROFESSIONAL_TITLE` | 抽取模型暂无此字段，挂公告级 |

### 每日变化摘要

Excel 增量导入的响应现在带上当天摘要：`{"result":{…},"digest":{…}}`。

```json
{"digest":{"reportDate":"2026-09-06","deadlineWindowDays":7,"suppressedUnchangedCount":97,
  "entries":[{"reason":"NEW","title":"信息中心技术岗","daysUntilDeadline":2,
              "strategyGrade":"MUST_TRACK","note":"本次采集新增岗位，报名还剩 2 天"}]}}
```

§10.7 只允许四个推送理由，未变化的岗位**不会**出现在 `entries` 里：

| `reason` | 触发条件 |
| --- | --- |
| `NEW` | 本次采集新增 |
| `UPDATED` | 内容指纹变化，硬条件需重新确认 |
| `DEACTIVATED` | 在最新完整快照中消失 |
| `DEADLINE_APPROACHING` | 内容未变，但报名剩余天数 ≤ `deadlineWindowDays`（默认 7） |

- 每个岗位最多出现一次；变化本身优先于截止提醒，但**每条都带 `daysUntilDeadline`**，
  读者不会因为被归类成"新增"而漏掉紧迫性。
- 已过截止日或日期未知的岗位不产生提醒，不会推"还剩 -3 天"。
- `suppressedUnchangedCount` 说明今天扫过但没什么可说的条数——它是个计数，不是列表。

`JobUpsertResult.changes` 逐岗记录本次变化类型（含 `UNCHANGED`）。过滤是摘要的职责，
入库阶段如实产出全部变化。

### 跨年度岗位族与关注清单

| 用途 | 端点 |
| --- | --- |
| 岗位族（跨年度出现记录） | `GET /job-lineages` |
| 目标年度关注清单 | `GET /watchlist?targetYear=2027` |

岗位族由 `job_posting` 与 `recruitment_event` 推导，不单独建表。归并键是
`单位名 + 技术族 + 岗位名称`（归一化后哈希），**刻意保守**：岗位名称实质改变就分成两族。
归并不足只会让信号偏弱，归并过度会凭空造出"连续招聘"的假趋势。

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

**没有概率字段。** §11 把"没有历史样本支撑的精确上岸概率"列为非目标，因此只给可观测的
模式加依据。观测窗口取全部已落库数据的年度范围——只导入了一年数据时，所有族都是
`INSUFFICIENT_HISTORY`，而不会被读成"没有再现规律"。

该信号同时喂给评分的 `FUTURE` 维度；没有历史时该维度仍为 `INSUFFICIENT_DATA`。

### 多维评分与策略等级

`opportunity.scorecard` 按 §6.2 分别给出六个维度，**没有总分字段**——单一匹配分是该节
明令禁止的。维度之上只输出一个由规则推导的 `strategyGrade`。

```json
{"scorecard":{
  "strategyGrade":"MUST_TRACK",
  "gradeRationale":"事业编岗位且岗位内容匹配度达标，列入主攻",
  "dimensions":{
    "FIT":{"value":100,"basis":"ESTIMATED","rationale":"专业精确命中岗位目录；…"},
    "STABILITY":{"value":95,"basis":"ESTIMATED","rationale":"用工身份 ESTABLISHMENT…"},
    "FUTURE":{"basis":"INSUFFICIENT_DATA","rationale":"缺少跨年度招聘历史…"}
  }}}
```

- `basis` 为 `MEASURED`／`ESTIMATED`／`INSUFFICIENT_DATA`。§6.2 要求估计值自报为估计。
- `INSUFFICIENT_DATA` 的维度**不带 value**，应显示为"数据不足"而非 0 分。
- `PREPARATION_COST` 是成本：值越高代表准备成本越高，方向与其余五维相反。
- 当前 `FUTURE` 与 `PREPARATION_COST` 恒为数据不足——跨年度历史、竞争观测、考试形式
  与报名窗口尚未建模。

`strategyGrade` 由规则推导，不是分数阈值加权，顺序 fail-closed：

| 条件 | 等级 |
| --- | --- |
| 资格 `INELIGIBLE` 或 `CONFLICTING_EVIDENCE` | `REJECT` |
| 分池 `EXCLUDED` | `REJECT` |
| 资格 `NEEDS_CONFIRMATION` 或 `CONDITIONAL` | `VERIFY_FIRST` |
| 分池证据不足或 `UNKNOWN` | `VERIFY_FIRST` |
| 资格通过 + T1 + `FIT` ≥ 60 | `MUST_TRACK` |
| 资格通过 + T1 且 `FIT` < 60 | `APPLY` |
| 资格通过 + T2 | `APPLY` |
| 资格通过 + T3 | `BACKUP` |

分池好不会自动升级：T1 但匹配度不达标仍是 `APPLY`。

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
- “专业门类/专业类/相关专业”不会伪装成精确专业命中，该条规则判为 `CONDITIONAL`，整体资格进入 `CONDITIONAL`，等待权威专业分类表或招录单位确认。
