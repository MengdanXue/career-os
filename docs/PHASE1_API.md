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
