# Career OS

面向浙江、重点杭州的稳定技术岗位智能职业决策系统。

当前主干已完成 [Master Product & Engineering Specification](docs/CAREER_OS_MASTER_SPEC.md) 的 Phase 0，并进入 Phase 1 Java MVP：岗位、单位、候选人 CRUD，官方 Excel 增量导入，资格评估、基础排序和 Opportunity Tracker。

## 主干模块

- `career-domain`：8 个核心领域模型、部分日期值对象、确定性资格规则。
- `career-application`：仓储端口。
- `career-infrastructure`：PostgreSQL、Flyway、Spring Data JPA、Testcontainers。
- `career-web`：REST API；仍不包含前端、爬虫或 LLM。

`crawler-service` 与 `agent-app` 是早期实验资产，当前从 Maven reactor 中隔离并保留，参见 [ADR-0001](docs/adr/ADR-0001-phase-0-mainline.md)。

## 验证

要求 Java 21、Maven 3.9+ 和 Docker：

```powershell
mvn clean test
```

测试覆盖年龄边界、学历、精确专业、毕业届别、工作年限、30 条真实 Golden Jobs、Excel 新增/重复/变更/下线差异、CRUD/资格决策 API，以及在临时 PostgreSQL 16 上执行 Flyway 和校验 JPA 映射。

Phase 1 API 与导入约定见 [Phase 1 API](docs/PHASE1_API.md)。
