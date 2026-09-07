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
{
  "recruitmentEventId": "<event-uuid>",
  "inserted": 2, "updated": 1, "unchanged": 3, "deactivated": 1,
  "errors": [],
  "warnings": [
    {"sheet":"岗位表","row":18,"field":"年龄","rawValue":"1990年1月1日以后出生","message":"年龄以出生日期表述，无法确定年龄上限"}
  ]
}
```

`errors` 与 `warnings` 语义不同：

- `errors` 是行级失败，该行**不会**导入，并且会禁止本批次的自动下线。
- `warnings` 是行级存疑，该行仍然导入，但对应的硬性条件字段**留空**待人工确认，不影响自动下线。

差异语义：

- 稳定岗位键优先由 `公告 URL + 单位 + 官方岗位代码` 生成；没有代码才使用岗位名。
- 内容指纹覆盖岗位名、人数、学历、专业、年龄、经验、招聘对象、用工性质、职责和地点。
- 相同稳定键、相同指纹为 `unchanged`；指纹变化为 `updated`。
- 同一公告完整重导时，原有但本次缺失的岗位标记 `active=false`，计入 `deactivated`。
- 无法识别表头或存在行错误时禁止自动下线，防止坏文件污染历史状态。
- 未明确写明编制/用工性质时保存为 `UNKNOWN`，不根据招聘单位类型推断。
- “专业门类/专业类/相关专业”不会伪装成精确专业命中，资格结果进入 `UNCERTAIN`。

## 硬性条件字段的解析边界

年龄上限和最低工作年限直接决定 `ELIGIBLE / INELIGIBLE`，因此只接受语义明确的表述，读不懂就留空并产生 `warnings`，绝不退化成“取单元格里第一个数字”。

| 单元格原文 | `maximumAge` | 说明 |
|---|---|---|
| `35周岁以下` / `年龄不超过35周岁` | `35` | 明确上限 |
| `1990年1月1日以后出生，年龄不超过35周岁` | `35` | 先剔除日期片段，不会取到 1990 |
| `年龄在18周岁以上、35周岁以下` | `35` | 取区间上界，不会取到下界 18 |
| `1990年1月1日以后出生` | `null` + warning | 仅以出生日期表述，需人工确认 |

| 单元格原文 | `minimumExperienceYears` | 说明 |
|---|---|---|
| `3年以上相关工作经验` | `3` | 数字紧邻“年” |
| `2025年应届毕业生` | `null` | 日历年份不会变成工作年限门槛 |
| `工作经历不限` | `null` | 明确无要求 |
| `2年以上工作经历，其中3年以上管理经历` | `null` + warning | 多个候选值，需人工确认 |

留空时 `EligibilityEvaluator` 的对应规则返回“无要求”或 `UNCERTAIN`，不会给出无证据的排除结论。

`ageReferenceDate`（年龄计算基准日）只来自调用方显式传入的参数。抽取管道不提供该事实时留空，不会用报名截止日冒充。
