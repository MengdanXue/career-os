# Career OS

面向浙江、重点杭州的稳定技术岗位智能职业决策系统。它不是通用招聘爬虫，而是围绕事业单位、高校科研技术岗、医院信息中心和国企数字平台，保存官方证据、识别岗位变化并支持人工复核的职业决策 Agent。

## 当前状态

工程基础与个人半体制规划工作台已经可以运行，但官方历史来源仍在持续补采，不能把当前岗位数量理解为完整市场总量。当前版本首先保证结论正确：采集到的记录只是“原始岗位”，只有经过解析、证据核验、目标范围确认并明确准入后，才会成为可排名、可推送的“可信机会”。因此，原始岗位数量不会再被当作 T1/T2/T3 机会数量。

当前主干具备：

- Phase 1：岗位、单位、候选人、资格评估和 Opportunity Tracker；官方 Excel 增量导入支持新增、变更、未变化和下线。
- Phase 2：HTML/PDF 证据解析、可选 LLM 结构化抽取、JSON Schema 校验、证据定位、Review Queue、幂等复用、OpenAPI 和采集指标。
- Phase 3A：浙江省、杭州市人社官方源定时增量采集；稳定公告键、SHA-256 内容指纹、条件请求、附件路由、PostgreSQL 分布式锁，以及新增/变更/下线变化流。
- Phase 3B Wave 1：把官方源接入升级为可审计的年度证据覆盖。杭电、浙江工商大学和杭州市第一人民医院已进入真实采集链路；列表翻页、年度停止原因、岗位行级失败、接入检查点和 `已接入 / 部分接入 / 采集失败 / 未接入` 状态都会持久化并展示，不能再用“目录里有这个 URL”冒充完成接入。只有列表契约、2024—2026 回溯、回溯后的独立增量运行和新鲜成功记录同时成立，来源才会投影为已接入。
- Phase 4A：确定性硬资格门槛、六维岗位匹配、证据感知稳定性、T1/T2/T3 分层、版本化决策快照、排名 API，以及带无模型回退的受控自然语言查询入口；这些能力只对已准入岗位运行。
- Phase 4B：可运行的 React 决策工作台，覆盖资料入口、今日变化、机会池、岗位档案、岗位库更新、人工复核和 Agent 入口，并明确区分原始岗位库与可信机会池。
- Phase 5A：个人半体制职业规划工作台。基于 1992-12-31 完整生日、计算机本科、境外计算机硕士在读且预计 2027 毕业等可核验事实，把本人明确识别为“2027 应届生 + 社会招聘”双通道，生成四条路线、逐年年龄窗口、2024—2026 历史实际条件、2027 类比判断，以及公告、报名、资格审查、缴费、准考证、笔试、专业测试、面试、体检、考察、公示、聘用十二阶段流程和行动时间线。目标年类比会同步推进年龄参考日和已核验工作经历时长，不再沿用历史年份的年龄或经历结论。
- Phase 5B Slice 1：个人事实与今日行动闭环。首页只展示零至三项与本人有关的截止、资格证据或目标岗位变化；画像页按岗位影响排序工作经历、政治面貌、预计毕业月份、留服认证、职称、技能和研究证据缺口。空值不会被确认成“明确没有”；硬资格只使用已确认、逐段核验的全职日期区间，并按官方资格参考日合并计算完整月份，旧的总工作年数仅作提示。
- Phase 5B Slice 2：画像变更后的岗位差异闭环。保存并确认资料后，系统针对旧版本已有结论的同一批岗位、同一 `asOf` 重算，展示“新增可报 / 减少待确认 / 新增不可报”、受影响岗位和逐项原因。旧快照不存在时计数返回未知而不是伪造零；重算失败不回滚已保存画像，也不会把旧岗位结论标记为当前结果。
- Opportunity Admission Gate（V9/V10）：持久化记录解析质量、目标范围、准入原因和人工核验状态；岗位内容变化后自动撤销旧准入，用工身份未知时禁止进入机会池，避免过期或不完整结论继续排名。
- Java 21：编译与运行均使用 Java 21，Spring 任务执行器启用虚拟线程，适合并发下载、文档解析和数据库等待等 I/O 密集工作。

当前阶段没有引入全网爬虫、登录/CAPTCHA 或自动投递。杭州 Wave 2 已把目标市场目录扩展到 28 个，其中包含杭州 10 个 P0 区和 3 个 P2 县市；6 个来源已绑定真实采集器，运行态为已接入 1、部分接入 5、采集失败 0，另外 22 个目标明确显示为未接入。西湖区招聘信息已通过官方 JCMS 列表契约进入采集链路，浙江政务云官方附件域名已按来源精确放行；其余区县仍待逐个核验官网契约，页面不会把“目录中有名称”误报成“已经采集”。资格复审、成绩、面试、体检、考察、公示和聘用公告会作为招聘生命周期文档单独保存，只有唯一匹配时才回写原招聘事件，未匹配和歧义项会保留审计状态。

Phase 4A 的模型不是决策者：资格、分数、层级和证据均由 Java 规则计算。模型默认关闭，启用后也只负责润色已经生成的解释，失败时自动回退到确定性中文说明。候选人事实逐项确认、岗位字段级证据和目标年类比已经进入主流程；完整的留服/专业目录权威映射、更多来源接入、Golden Jobs 扩面和申请跟踪仍是后续核心工作，因此当前版本是可使用、可审计的个人规划与证据研究台，不等于浙江/杭州全市场采集完成。

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

如只启用 Phase 4A Agent 的可选模型润色，还需设置：

```powershell
$env:CAREER_OS_AGENT_LLM_ENABLED='true'
```

## 构建与运行

要求 Java 21、Maven 3.9+、PostgreSQL 16。运行 Docker 还可执行 Testcontainers 集成测试。

Windows 本地使用推荐直接执行：

```powershell
pwsh -NoProfile -File scripts/start-career-os.ps1
```

脚本使用 Docker 启动项目专属 PostgreSQL（默认本机端口 `55432`），自动构建有变化的前后端、等待健康检查并打开网页。停止且保留数据：

```powershell
pwsh -NoProfile -File scripts/stop-career-os.ps1
```

```powershell
mvn clean test
mvn -DskipTests package
```

应用启动后可运行个人决策正确性与真实浏览器验收：

```powershell
pwsh -NoProfile -File scripts/career_correctness_acceptance.ps1 -BaseUrl http://localhost:8080
python scripts/planning_browser_acceptance.py
pwsh -NoProfile -File scripts/hangzhou_wave2_slice_a_acceptance.ps1 -BaseUrl http://localhost:8080 -RunSources
```

2026-08-24 的当前验收数据库已迁移到 V40。真实运行态共有 28 个目标、6 个已配置来源：已接入 1、部分接入 5、采集失败 0、未接入 22，官网访问正常 6 个。西湖区修复官方附件域名后，第一轮运行新增 1 个 Excel 附件、复用 2 个文档、失败 0；第二轮复用 3 个文档、新增 0、更新 0、失败 0。该 Excel 已解析为 24 个正式事业单位岗位，其中“信息管理”岗位的研究生专业要求直接包含“计算机科学与技术”。当前三份西湖材料没有生命周期标题，实测后续公告为 0；系统会明确显示零，不伪造公示或面试记录。历史失败记录仍保留用于审计，但页面会标明“历史”。当前岗位库仍不能代表杭州全市场总量。T1/T2/T3 结果继续受准入门与证据条件约束，不会把原始记录直接包装成可报机会。

官方源兼容性检查默认不访问公网，需要时显式运行：

```powershell
mvn -Pacquisition-live "-Dcareer-os.acquisition.live-smoke-enabled=true" "-Dtest=OfficialSourceLiveSmokeTest" test
```

启动应用前设置数据库连接；Flyway 会自动迁移到当前 schema：

```powershell
$env:CAREER_OS_DB_URL='jdbc:postgresql://localhost:5432/career_os'
$env:CAREER_OS_DB_USER='career_os'
$env:CAREER_OS_DB_PASSWORD='career_os'
java -jar career-web\target\career-web-0.1.0-SNAPSHOT.jar
```

运行后可访问：

- 今天：`http://localhost:8080/`
- 我的半体制规划：`http://localhost:8080/plan`
- 我的资料：`http://localhost:8080/profile`
- 健康检查：`http://localhost:8080/actuator/health`
- OpenAPI：`http://localhost:8080/v3/api-docs`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- 指标：`http://localhost:8080/actuator/metrics`

个人行动接口为 `GET /api/v1/candidates/{candidateId}/personal-actions?asOf=YYYY-MM-DD`，画像证据任务接口为 `GET /api/v1/candidates/{candidateId}/evidence-tasks?asOf=YYYY-MM-DD`。画像保存并确认后，前端调用 `POST /api/v1/candidates/{candidateId}/decision-change-summaries/{previousProfileVersion}?asOf=YYYY-MM-DD` 重算旧版本所覆盖的同一批岗位。三者都由确定性规则生成；规划页和任意已分析岗位详情已统一展示历史实际条件、2027 类比结果和十二阶段招考流程。后续体检、考察、公示等独立公告已具备保守自动关联、未匹配/歧义留存和来源健康统计；扩大到其余杭州来源以及完整申请跟踪仍属于后续工作。

接口说明见 [Phase 1 API](docs/PHASE1_API.md)、[Phase 2 API](docs/PHASE2_API.md)、[Phase 3 增量采集 API](docs/PHASE3_API.md)、[Phase 4A 决策智能与 Agent API](docs/PHASE4A_API.md)、[Phase 4B 决策工作台](docs/PHASE4B_WORKBENCH.md) 和 [Phase 5A 半体制职业规划](docs/PHASE5A_CAREER_PLANNER.md)。完整产品边界见 [产品需求基线](docs/product-requirements.md)。
