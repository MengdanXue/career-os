# Career OS

面向浙江、重点杭州的稳定技术岗位智能职业决策系统。它不是通用招聘爬虫，而是围绕事业单位、高校科研技术岗、医院信息中心和国企数字平台，保存官方证据、识别岗位变化并支持人工复核的职业决策 Agent。

当前主干已完成：

- Phase 1：岗位、单位、候选人、资格评估和 Opportunity Tracker；官方 Excel 增量导入支持新增、变更、未变化和下线。
- Phase 2：HTML/PDF 证据解析、可选 LLM 结构化抽取、JSON Schema 校验、证据定位、Review Queue、幂等复用、OpenAPI 和采集指标。
- Java 21：编译与运行均使用 Java 21，Spring 任务执行器启用虚拟线程，适合并发下载、文档解析和数据库等待等 I/O 密集工作。

当前阶段没有引入全网爬虫、定时调度器、自动投递或前端。采集源自动扫描将在后续阶段接到现有的增量入库和审核管道。

## 模块

- `career-domain`：纯 Java 领域模型、证据模型、资格规则和审核状态机。
- `career-application`：采集、审核、岗位写入用例及端口。
- `career-infrastructure`：PostgreSQL、Flyway、Spring Data JPA、HTML/PDF/Excel 解析和内容寻址文件存储。
- `career-web`：REST API、可选 Spring AI/OpenAI 适配器、OpenAPI 和 Micrometer 指标。

`crawler-service` 与 `agent-app` 是早期实验资产，已从 Maven reactor 隔离，参见 [ADR-0001](docs/adr/ADR-0001-phase-0-mainline.md)。

## 默认离线模式

系统默认 `CAREER_OS_LLM_ENABLED=false` 且 `CAREER_OS_AI_CHAT_MODEL=none`。此模式不会访问任何模型：HTML/PDF 仍会保存原文件、抽取可定位的证据片段并创建待审核提案，但不会猜测岗位硬条件或用工性质。

原始文件按 SHA-256 内容寻址，默认保存在 `${user.dir}/var/artifacts`；可通过 `CAREER_OS_ARTIFACT_ROOT` 修改。该目录不会提交到 Git。

启用真实模型需要显式设置：

```powershell
$env:OPENAI_API_KEY='<your-key>'
$env:CAREER_OS_AI_CHAT_MODEL='openai'
$env:CAREER_OS_LLM_ENABLED='true'
$env:CAREER_OS_AI_MODEL='gpt-5-mini'
```

模型输出必须通过 JSON Schema 和证据校验；LLM 不能覆盖确定性资格规则。需要强制模型成功时，在上传元数据中设置 `requireModel=true`。

## 构建与运行

要求 Java 21、Maven 3.9+、PostgreSQL 16。运行 Docker 还可执行 Testcontainers 集成测试。

**本地没有 Docker 时，持久化、迁移与并发用例会被静默跳过，而 Maven 仍然打印 `BUILD SUCCESS`。**
GitHub Actions（`.github/workflows/ci.yml`）在有 Docker 的环境跑 `mvn -B -ntp verify`，
并用 `.github/scripts/check_skipped_tests.py` 在白名单之外出现任何跳过时让构建失败。

```powershell
mvn clean test
mvn -DskipTests package
```

启动应用前设置数据库连接；Flyway 会自动迁移到当前 schema：

```powershell
$env:CAREER_OS_DB_URL='jdbc:postgresql://localhost:5432/career_os'
$env:CAREER_OS_DB_USER='career_os'
$env:CAREER_OS_DB_PASSWORD='career_os'
java -jar career-web\target\career-web-0.1.0-SNAPSHOT.jar
```

运行后可访问：

- 健康检查：`http://localhost:8080/actuator/health`
- OpenAPI：`http://localhost:8080/v3/api-docs`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- 指标：`http://localhost:8080/actuator/metrics`

接口说明见 [Phase 1 API](docs/PHASE1_API.md) 和 [Phase 2 API](docs/PHASE2_API.md)。完整产品边界见 [产品需求基线](docs/product-requirements.md)。
