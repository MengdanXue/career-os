# V5 数据库升级说明

V5 将 `recruitment_event.source_url` 定义为招聘事件的唯一来源键，用来保证同一公告的不同内容版本不会并发创建多个招聘事件。

正常的新数据库会直接完成迁移。若旧数据库已经存在相同 `source_url` 的多条招聘事件，V5 会明确终止启动，不会自动猜测应保留哪条记录或擅自移动岗位。

升级前先查重：

```sql
SELECT source_url, count(*) AS event_count, array_agg(id ORDER BY created_at) AS event_ids
FROM recruitment_event
GROUP BY source_url
HAVING count(*) > 1;
```

对每组重复项人工确认主事件后：

1. 将其余事件关联的 `job_posting.recruitment_event_id` 更新为主事件 ID；
2. 合并各事件的 `evidence_ids`、有效日期和公告元数据；
3. 删除已经没有岗位引用的重复事件；
4. 再次运行上述查重 SQL，确认返回 0 行；
5. 重新启动应用，让 Flyway 执行 V5。

生产操作前必须备份数据库。V5 的故意失败是数据保护机制，不应通过删除 Flyway 历史或直接标记迁移成功来绕过。
