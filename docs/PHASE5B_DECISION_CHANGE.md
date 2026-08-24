# Phase 5B：画像变更后的岗位差异

## 用户流程

画像页按照以下顺序执行，两个阶段的失败语义彼此独立：

1. 保存画像草稿；
2. 确认当前候选人事实；
3. 记录保存前的 `profileVersion`；
4. 以固定 `asOf` 重算旧版本已有结论的同一批岗位；
5. 展示新增可报、减少待确认、新增不可报、受影响岗位和变化原因。

事实保存或确认失败时，页面保留可重试的编辑表单。事实已经确认、但岗位重算失败时，页面保留新画像，明确说明旧岗位结论不是当前结果，并只重试派生结论。

## API

```http
POST /api/v1/candidates/{candidateId}/decision-change-summaries/{previousProfileVersion}?asOf=2026-08-24
```

路径中的版本是保存前的候选人画像版本。服务端读取候选人当前版本，并只重算旧版本快照中出现过的岗位 ID。

有可比较历史时：

```json
{
  "candidateId": "...",
  "previousProfileVersion": "profile-old",
  "currentProfileVersion": "profile-current",
  "asOf": "2026-08-24",
  "available": true,
  "message": null,
  "newlyEligibleCount": 1,
  "resolvedUncertaintyCount": 2,
  "newlyIneligibleCount": 0,
  "affectedJobs": [
    {
      "jobId": "...",
      "title": "信息技术岗位",
      "organizationName": "杭州市信息中心",
      "previousStatus": "UNCERTAIN",
      "currentStatus": "ELIGIBLE",
      "reasons": ["工作经历：待确认 → 可报（经历已核验）"],
      "deepLink": "/opportunities/..."
    }
  ]
}
```

旧版本没有决策快照时，`available=false`，三个差异计数为 `null`，不能解释成零变化。

## 正确性边界

- 工作年限只按官方报名截止日计算；截止日缺失时保持待确认。
- `assessedAt` 是实际计算时间，不再被资格截止日替换。
- 决策输入身份会同步写入资格、匹配、稳定性和总决策四类快照；官方截止日修正后可以生成完整的新快照，不会复用旧结论或触发数据库唯一键冲突。
- 只有岗位内容指纹和资格截止日身份都一致时，差异才会归因于画像修改；否则 `available=false`，计数保持 `null`。
- 页面跨“草稿保存成功、事实确认失败”的重试保留最初的已确认画像版本，不会把中间草稿误当成比较基线。
- 重算期间若画像再次变化，API 返回 `409 DECISION_COMPARISON_CONFLICT`，避免混用两个画像版本。
- 政治面貌硬条件按字段和语句识别；“限中共党员；年龄不限”仍会触发政治面貌确认，“党员优先”和“党员或民主党派”不会被误判为硬限制。
- 差异是可重建读模型，不新增可过期的差异历史表。
