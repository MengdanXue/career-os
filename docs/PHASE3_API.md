# Phase 3A 增量采集 API

Base path：`/api/acquisition`。Phase 3A 只启用浙江省人社厅和杭州市人社局两个事业单位招聘官方源，不是全网爬虫。

## 运行模型

- 浙江源每天北京时间 `08:10` 扫描，杭州源每天 `08:20` 扫描；调度器每分钟分发到期源。
- 同一来源使用 PostgreSQL advisory lock，多个实例不会同时采集；未获得锁的运行记录为 `SKIPPED_LOCKED`。
- 公告规范化 URI 是稳定文档键；HTTP 跳转后的 CDN 地址不会改变它。
- 内容使用 SHA-256 指纹。相同内容只更新“已看到”状态，不重复解析、入库或推送。
- HTML/PDF 进入 Phase 2 证据抽取与 Review Queue；XLS/XLSX 进入确定性岗位增量导入。
- 超时、`429`、`5xx`、列表页缺失和报名截止都不会触发下线。只有同一详情连续两次返回 `404/410` 且间隔至少六小时，才生成一次 `DEACTIVATED`。

## 查询来源

```bash
curl http://localhost:8080/api/acquisition/sources
```

响应不暴露选择器等内部配置：

```json
[
  {
    "id": "01992f09-0000-7000-8000-000000000301",
    "code": "ZJ_HRSS_INSTITUTION",
    "name": "浙江省人力资源和社会保障厅事业单位招聘",
    "entryUri": "https://rlsbt.zj.gov.cn/col/col1229743683/index.html",
    "enabled": true,
    "cronExpression": "0 10 8 * * *",
    "timeZone": "Asia/Shanghai",
    "nextDueAt": "2026-08-16T00:10:00Z"
  }
]
```

## 手工触发一次采集

```bash
curl -i -X POST http://localhost:8080/api/acquisition/sources/01992f09-0000-7000-8000-000000000301/runs
```

接口同步完成本次采集并返回 `202 Accepted`，`Location` 指向运行记录：

```json
{
  "id": "<run-uuid>",
  "sourceId": "01992f09-0000-7000-8000-000000000301",
  "trigger": "MANUAL",
  "status": "SUCCEEDED",
  "discoveredCount": 12,
  "fetchedCount": 12,
  "unchangedCount": 11,
  "addedCount": 1,
  "updatedCount": 0,
  "deactivatedCount": 0,
  "failedCount": 0
}
```

手工运行不会移动原定的下一次调度时间。

## 查询运行与历史

```bash
curl http://localhost:8080/api/acquisition/runs/<run-uuid>
curl "http://localhost:8080/api/acquisition/runs?sourceId=<source-uuid>&status=SUCCEEDED&page=0&size=50"
```

历史查询还支持 ISO-8601 `from`、`to`。`page` 从 0 开始，`size` 为 1–200。状态包括 `RUNNING`、`SUCCEEDED`、`PARTIALLY_SUCCEEDED`、`FAILED` 和 `SKIPPED_LOCKED`。

## 消费新增、变更和下线流

首次读取：

```bash
curl "http://localhost:8080/api/acquisition/changes?size=50"
```

可使用 `sourceId` 和逗号分隔的 `types=ADDED,UPDATED` 过滤。响应中的 `nextCursor` 是不透明游标；有值时原样传回：

```bash
curl "http://localhost:8080/api/acquisition/changes?size=50&cursor=<nextCursor>"
```

```json
{
  "items": [
    {
      "changeType": "UPDATED",
      "previousFingerprint": "<sha256>",
      "currentFingerprint": "<sha256>",
      "canonicalUri": "https://rlsbt.zj.gov.cn/art/2026/8/15/art_1229743683_700001.html",
      "jobDeltaSummary": {"inserted": 0, "updated": 2, "unchanged": 8, "deactivated": 0},
      "occurredAt": "2026-08-15T00:15:00Z"
    }
  ],
  "nextCursor": "<opaque-base64url-or-null>"
}
```

游标内部使用 `occurredAt + UUID` 排序，即使多条变化时间相同也不会跳项。消费者只保存成功处理后的游标，即可做到只推送变化。

## 配置与安全停用

常用环境变量：

- `CAREER_OS_ACQUISITION_DISPATCH_DELAY_MS`：到期源分发间隔，默认 `60000`。
- `CAREER_OS_ACQUISITION_SCHEDULING_ENABLED`：是否启动定时采集，默认 `true`；维护窗口可设为 `false`。
- `CAREER_OS_ACQUISITION_DISPATCH_BATCH_SIZE`：每轮最多来源数，默认 `20`。
- `CAREER_OS_ACQUISITION_LOCK_POOL_SIZE`：采集锁专用连接池，默认 `2`。
- `CAREER_OS_ACQUISITION_LOCK_CONNECTION_TIMEOUT_MS`：锁连接超时，默认 `5000`。
- `CAREER_OS_ACQUISITION_LOCK_POLL_INTERVAL_MS`：锁轮询间隔，默认 `50`。
- `CAREER_OS_HTTP_CONTACT`：加入 User-Agent 的运维联系信息。
- `CAREER_OS_ARTIFACT_ROOT`：HTML、PDF、Excel 内容寻址文件根目录。

官网结构变化时，先在数据库中将对应 `recruitment_source.enabled` 设为 `false`，保留历史文档、运行和变化记录；更新并通过离线选择器测试和实网 smoke 后再启用。不要删除来源或用空列表推断岗位下线。

## 监控与错误

Actuator 提供：`careeros.acquisition.runs`、`careeros.acquisition.documents`、`careeros.acquisition.fetch.duration`、`careeros.acquisition.processing.failures` 和 `careeros.acquisition.lock.skipped`。错误使用 `application/problem+json`；非法游标/分页为 `400`，未知来源或运行为 `404`。
