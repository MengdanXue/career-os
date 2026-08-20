# Phase 4A 决策智能与 Agent API

Phase 4A 面向浙江、重点杭州的事业单位、高校、医院信息中心和国企数字技术岗位。系统先执行确定性硬资格判定，再计算岗位匹配、稳定性与证据覆盖率；模型只可选地润色解释，不能改写结构化结论。

所有响应中的分数都是“机会决策指数”，不是录取概率。

## 决策模型

一次决策快照由以下输入唯一确定：

```text
候选人 ID + 岗位 ID + 候选人版本 + 岗位内容指纹 + 评估器版本
```

相同输入重复评估会复用已有快照；候选人资料、岗位内容或评估规则变化时会创建新版本并保留历史。

决策顺序：

1. 硬资格：年龄、学历、精确专业、毕业届别、工作年限和职称。
2. 匹配度：专业 25、技能 20、经验 20、研究方向 10、岗位名称 10、个人偏好 15。
3. 稳定性：用工身份、经费、组织、政策、业务波动、裁员风险和合同风险；未知事实得 0 分并降低覆盖率。
4. 机会层级：有明确证据的事业编为 T1；半体制单位正式合同或人事代理为 T2；派遣、项目制、普通市场化岗位或证据不足为 T3；硬资格不符为 `EXCLUDED`。

## 更新候选人决策资料

沿用候选人资源：

```http
PUT /api/v1/candidates/{candidateId}
Content-Type: application/json
```

除基本资料外，Phase 4A 支持：

- `skills`
- `researchKeywords`
- `targetJobFamilies`
- `preferredOrganizationTypes`
- `preferredLocations`
- `acceptedEmploymentTypes`
- `profileVersion`

修改影响决策的字段时必须同时更新 `profileVersion`，例如从 `profile-v2` 更新为 `profile-v3`。

## 创建或复用岗位决策

```http
POST /api/v1/candidates/{candidateId}/job-decisions/{jobId}
```

返回资格状态、T1/T2/T3 层级、推荐状态、匹配度、稳定性、各维度证据、未知项警告、版本和岗位内容指纹。相同输入重复调用返回相同 `decisionId`。

## 读取当前版本决策

```http
GET /api/v1/candidates/{candidateId}/job-decisions/{jobId}
```

该接口只读取当前候选人版本与当前岗位指纹对应的快照，不会创建数据。没有快照时返回 `404 DECISION_NOT_FOUND`；需要计算时调用 POST。

## 排名与筛选

```http
GET /api/v1/candidates/{candidateId}/job-decisions?tier=T1&location=杭州&jobFamily=INFORMATION_SYSTEMS&page=0&size=20
```

支持参数：

| 参数 | 说明 |
|---|---|
| `tier` | `T1`、`T2`、`T3` 或 `EXCLUDED` |
| `location` | 地点包含匹配 |
| `jobFamily` | `SOFTWARE`、`DATA`、`AI`、`IT_OPERATIONS`、`INFORMATION_SYSTEMS` 等 |
| `page` | 从 0 开始 |
| `size` | 1—100 |
| `includeExcluded` | 默认 false |

默认排序为层级、匹配度、稳定性、证据覆盖率、截止日期和岗位 ID。排名过程中会为尚无当前快照的有效岗位执行评估。

## 有边界的自然语言 Agent

```http
POST /api/v1/candidates/{candidateId}/agent-queries
Content-Type: application/json

{
  "question": "找杭州信息中心 T1 岗位",
  "limit": 5
}
```

Agent 将问题约束为地点、层级和岗位族筛选，调用同一套确定性排名工具，并返回自然语言说明与完整结构化决策。无模型时功能完整可用；模型失败时自动回退，并设置 `fallbackUsed=true`。

默认关闭模型润色。启用时设置：

```powershell
$env:OPENAI_API_KEY='<your-key>'
$env:CAREER_OS_AI_CHAT_MODEL='openai'
$env:CAREER_OS_AGENT_LLM_ENABLED='true'
```

模型没有写入工具，也不能修改响应中的资格、层级、分数、覆盖率和证据字段。

## 稳定错误码

| HTTP | code | 含义 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 分页、筛选或问题不合法 |
| 404 | `CANDIDATE_NOT_FOUND` | 候选人不存在 |
| 404 | `JOB_NOT_FOUND` | 岗位不存在 |
| 404 | `DECISION_NOT_FOUND` | 当前输入版本尚未评估 |
| 409 | `DATA_CONFLICT` | 唯一键或外键冲突 |

## 当前边界

- T1/T2 只在用工身份和岗位证据满足明确规则时给出；不会根据单位名称猜编制。
- 单位经费、政策和组织稳定事实需要可核验证据；缺失时保持未知。
- 目前 Agent 是受控查询与解释，不执行自动投递、登录、材料提交或外部写操作。
- 旧 `/api/v1/eligibility-assessments` 与 `Opportunity.matchScore` 仅为 Phase 1 兼容接口，不是新排名的权威数据源。
