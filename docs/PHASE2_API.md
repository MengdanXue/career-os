# Phase 2 采集与审核 API

Base path：`/api/v1`。系统默认离线运行；以下接口不会因为未配置模型而访问外部服务。

## 提交 HTML 或 PDF

请求为 `multipart/form-data`，包含：

- `document`：`text/html`、`application/xhtml+xml` 或 `application/pdf`，最大 25 MB；
- `metadata`：JSON，必填 `sourceUrl`、`sourceTitle`、`capturedAt`，可选 `organizationId`、`recruitmentEventId`，以及默认 `false` 的 `requireModel`。

先创建 `metadata.json`：

```json
{
  "sourceUrl": "https://official.example.gov.cn/recruitment/123",
  "sourceTitle": "2026年公开招聘公告",
  "capturedAt": "2026-08-14T15:00:00Z",
  "requireModel": false
}
```

上传 HTML：

```bash
curl -i -X POST http://localhost:8080/api/v1/extractions \
  -F "document=@notice.html;type=text/html" \
  -F "metadata=@metadata.json;type=application/json"
```

上传 PDF：

```bash
curl -i -X POST http://localhost:8080/api/v1/extractions \
  -F "document=@guide.pdf;type=application/pdf" \
  -F "metadata=@metadata.json;type=application/json"
```

首次成功返回 `201 Created`；相同文件内容和同一抽取配置再次提交返回 `200 OK`、`reused=true`，并复用原 `id`，不会制造重复岗位或审核项。

```json
{
  "id": "<extraction-run-uuid>",
  "status": "REVIEW_REQUIRED",
  "reused": false,
  "evidenceId": "<evidence-uuid>",
  "reviewId": "<review-uuid>",
  "proposal": { "recruitmentEvent": {}, "organizations": [], "jobs": [] },
  "errorCode": null,
  "errorMessage": null
}
```

## 查询抽取结果

```bash
curl http://localhost:8080/api/v1/extractions/<extraction-run-uuid>
```

关键状态包括 `REVIEW_REQUIRED`、`VERIFIED`、`REJECTED` 和 `FAILED`。提案中的硬条件只有在具有可定位证据且通过校验后才能进入正式岗位表。

## Review Queue

分页查询待审核项：

```bash
curl "http://localhost:8080/api/v1/reviews?status=PENDING&page=0&size=20"
```

查询详情（包含原提案、问题和 HTML CSS/PDF 页码证据片段）：

```bash
curl http://localhost:8080/api/v1/reviews/<review-uuid>
```

每次动作都必须提交当前 `expectedVersion`，避免两位审核者覆盖彼此结果。版本过期返回 `409 Conflict`。

### 确认原提案

```bash
curl -X POST http://localhost:8080/api/v1/reviews/<review-uuid>/actions \
  -H "Content-Type: application/json" \
  -d '{"decision":"CONFIRM","expectedVersion":0,"note":"已核对官方原文"}'
```

### 修正后确认

将完整且符合 schema 的提案保存为 `corrected-proposal.json`：

```bash
curl -X POST http://localhost:8080/api/v1/reviews/<review-uuid>/actions \
  -H "Content-Type: application/json" \
  --data-binary '{"decision":"CORRECT","expectedVersion":0,"correctedPayload":'"$(cat corrected-proposal.json)"',"note":"按附件第2页修正"}'
```

在 PowerShell 中可先构造 JSON，避免 shell 引号问题：

```powershell
$proposal = Get-Content -Raw .\corrected-proposal.json | ConvertFrom-Json
$body = @{ decision='CORRECT'; expectedVersion=0; correctedPayload=$proposal; note='按附件第2页修正' } | ConvertTo-Json -Depth 30
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/reviews/<review-uuid>/actions' -ContentType 'application/json' -Body $body
```

### 拒绝提案

```bash
curl -X POST http://localhost:8080/api/v1/reviews/<review-uuid>/actions \
  -H "Content-Type: application/json" \
  -d '{"decision":"REJECT","expectedVersion":0,"note":"不是正式招聘公告"}'
```

### 请求更多证据

```bash
curl -X POST http://localhost:8080/api/v1/reviews/<review-uuid>/actions \
  -H "Content-Type: application/json" \
  -d '{"decision":"NEED_MORE_EVIDENCE","expectedVersion":0,"note":"缺少岗位附件"}'
```

`CONFIRM` 和 `CORRECT` 仅会写入通过 schema、证据与硬事实校验的岗位；`REJECT` 关闭该审核项；`NEED_MORE_EVIDENCE` 保留待审核状态并记录不可变审核动作。

## 输入预算与截断

送入模型的证据片段有字符预算（`career-os.extraction.llm.max-prompt-characters`，默认 40000，
环境变量 `CAREER_OS_LLM_MAX_PROMPT_CHARACTERS`）。上传上限是 25MB，若不设预算，一份大 PDF
必然超出模型上下文。

- 超预算时**整条丢弃**尾部片段，绝不截断片段内部文本——模型引用的 `evidenceFragmentId`
  必须始终对应完整 verbatim 原文，否则证据校验会拿残缺文本去比对。
- prompt 中会明确告知模型输入已截断，避免它把片段当成公告全文。
- 截断会产生一条 `INPUT_TRUNCATED` 复核项，该次抽取**不允许自动核验**，
  即使提案本身证据齐全、置信度达标，也必须进入人工复核。
- 修复轮回显的无效响应同样有上限，不会把一份超长坏响应整个塞回模型。

## 并发与事务边界

相同 `inputFingerprint` 的并发提交由 Postgres advisory lock 串行化，模型只会被调用一次。

模型调用**不在数据库事务内**：事务边界下沉到各持久化步骤自身，等待模型期间不占用主连接池的
连接。但模型调用仍在 advisory lock 内，因此 `career-os.extraction.lock-wait-timeout-ms`
（默认 30000）必须大于模型的最坏往返时间，否则并发提交相同文档会锁超时。

## 错误与可观测性

错误使用 `application/problem+json`：不支持格式为 `415`、文件过大为 `413`、提案无效为 `422`、并发版本冲突为 `409`、资源不存在为 `404`、强制模型但模型不可用为 `503`。

Actuator 指标包括 `extraction_runs`、`extraction_reuse`、`extraction_duration`、`llm_calls`、`llm_schema_failures` 和 `review_queue_size`。OpenAPI 位于 `/v3/api-docs`，交互文档位于 `/swagger-ui.html`。
