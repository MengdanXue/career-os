# ADR-0001: Phase 0 主干与原型隔离

- 状态：已接受
- 日期：2026-08-14

## 决策

`docs/CAREER_OS_MASTER_SPEC.md` 是当前产品与工程的权威基线。Phase 0 主干使用 Java 21、Spring Boot 3.5.16、PostgreSQL、Flyway、Spring Data JPA 和 Maven 多模块单体：

- `career-domain`：领域类型、值对象和确定性资格规则，不依赖 Spring。
- `career-application`：用例端口和仓储端口。
- `career-infrastructure`：PostgreSQL/Flyway/JPA 实现。
- `career-web`：Spring Boot 启动壳；Phase 0 不提供业务 API 或前端。

已有的 `crawler-service` 与 `agent-app` 是早期实验资产，完整保留但不进入 Phase 0 Maven reactor。采集与 LLM 要等 Golden Dataset、规则和数据边界稳定后再进入后续阶段。

## 工程歧义的处理

- Spring Boot 3 未指定补丁版本：固定为 3.5.16，保持在受维护的 3.5 线。
- 数据库测试环境未指定：使用 Testcontainers PostgreSQL；Docker 不可用时只跳过数据库集成测试，纯领域测试必须始终运行。
- 出生日期可能只有年月：领域使用部分日期值对象，边界无法唯一判定时该条规则返回 `UNKNOWN`，整体资格进入 `NEEDS_CONFIRMATION`，不伪造具体生日。
- `Source` 在总领域清单中出现、但 Phase 0 明确实施清单未包含：本阶段由 `Evidence` 与来源字段承载，独立 Source 聚合留到 Phase 1。

## 后果

Phase 0 的成功标准是可复现的数据模型、迁移、Golden fixtures 和边界测试，而不是抓取网站数量。历史原型不会丢失，也不会对新主干引入 Spring AI、浏览器或采集依赖。
