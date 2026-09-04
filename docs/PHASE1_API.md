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
