# 杭州采集底座审计：远程与本地基线

审计日期：2026-09-07。代码基线：本地 `codex/phase4b-career-workbench` 的 `175c77a`，迁移到 V77。本文是静态实现审计，不是新的真实采集验收；并行中的资格修复不提前计入完成。上次运行报告中的 30 个来源、10 COMPLETE / 7 NO_TARGET_RECORDS / 73 PARTIAL 均是旧语义历史观测，不能作为新版完成证明。

远程评审分支基于旧 Phase 2，资格解析与 CI 修复需要适配到本地后测试；其 V6/V7/V8 与本地同号迁移不能直接合入。本地主线已有多入口、指纹、历史覆盖和生命周期基础，实施应复用这些模块。本文不声称当前远程提交或本地服务状态已重新检查；主 Agent 的 Git 对照与运行验收另行记录。

下表路径相对仓库根目录，包目录简写为 D=`career-domain/src/main/java/com/careeros/domain/`、A=`career-application/src/main/java/com/careeros/application/`、I=`career-infrastructure/src/main/java/com/careeros/infrastructure/`、M=`career-infrastructure/src/main/resources/db/migration/`。阶段对应原实施计划新增 Phase A—F。

| # | 要求与现有证据 | 审计结论与缺口 | 阶段 |
| --- | --- | --- | --- |
| 1 | source registry、`I/acquisition/OfficialSourceCatalog.java`、`A/SourceConnectionProjector.java`、`scripts/hangzhou_source_batch_acceptance.ps1`；V45—V77 接入修复 | 有真实运行/复跑基础。旧“28/28”分母过时，需冻结实际来源清单和时间。连接状态只证明接入；旧部分成功不得提升总体完成 | A、F |
| 2 | `I/acquisition/ListingEntryContract.java` 的 PRIMARY/HISTORICAL/SECTOR/LIFECYCLE/CAMPAIGN_STATE；`ConfigurableSourceListingReader` 聚合 required entry | 多入口能力已实现，但未证明每个单位所有实际招聘、人事和考试栏目都登记。需逐来源渠道审计，不能仅看已配置入口全遍历 | B、F |
| 3 | `M/V1__phase0_core_schema.sql` 已有 recruitment_event；V5 source_url 唯一；V15 workbook_identity；`I/persistence/OfficialLifecycleDocumentService.java` 标题 stem 匹配 | 批次表已存在，无需重造。当前仍以 URL/工作簿标识为主要事件身份；跨部门转载无稳定批次多来源映射，生命周期匹配不是批次合并 | C |
| 4 | `I/persistence/DefaultJobUpsertService.java::stableKey` 使用 source/attachment identity + employer + codeOrTitle；`OfficialWorkbookIdentity` 稳定下载 URL | 能处理同来源重跑和部分临时附件 URL，不能完整满足 batch+单位+代码。无代码未包含关键条件；补充公告换 URL 可能重复 | C |
| 5 | `DefaultJobUpsertService::upsert` 执行 copy 后保存当前 job；`D/acquisition/AcquisitionChange.java` 保留文档指纹和 delta | 有变更计数与文档变化记录，没有可回放的不可变岗位 before/after 版本；核减/延期/取消/更正/递补/放弃未完整建模 | C、D |
| 6 | `D/DomainEnums.java::EmploymentType` 已含事业编、员额/备案、正式聘用、国企正式等；`OfficialJobFieldMapper`、`JobPosting` 存实际单位/地点 | 已有较丰富用工类型；需独立 recruitment_type 和逐岗位证据核验，不能把单位默认性质用于所有岗位 | C |
| 7 | `D/acquisition/AcquiredDocument.java` 仅 ANNOUNCEMENT/ATTACHMENT；`SourceArtifact` 有 checksum/storage；`I/acquisition/Phase2DocumentProcessor.java` 支持 HTML/PDF/XLS/XLSX/图片 | 附件不是完整语义清单；doc/docx/zip 无相应 processor 分支；缺统一 document_type/parser/extraction/confidence 状态。目录误判已有非岗位 schema 防线，需要扩展类型闭环 | B |
| 8 | `D/acquisition/ArtifactImportFailure.java` 含下载、解析、行错误；`A/AcquiredDocumentProcessor.java` 有 OCR_REQUIRED/UNSUPPORTED/FAILED；V3 有 review_item | 已有失败记录和复核组件。所有失败与扫描/图片尚未统一进入持久化文档复核状态；`PdfBoxDocumentParser` 空文本质量为 ACCEPTABLE，必须核对后续空文本护栏且不能解释为零岗位 | A、B |
| 9 | `I/artifact/FileSystemArtifactStore.java` 内容寻址原始字节；`A/AcquisitionService` 将详情/附件写入 artifact | 正文/附件快照基础可复用；`ConfigurableSourceListingReader` 对 JSON/列表页主要生成内存证据，没有统一持久化原始列表响应链，不能完整复现页面总数证明 | B |
| 10 | `D/acquisition/SourceYearCoverage.java` 含 PARTIAL/ACCESS_FAILED/COMPLETE/NO_TARGET_RECORDS；`ListingEntryContract.knownArchiveGapYears` | 缺 VERIFIED_NO_DATA/ARCHIVE_UNAVAILABLE/MOVED/UNKNOWN 语义。现 supportsAbsenceConclusion 对旧 COMPLETE 和 NO_TARGET_RECORDS 为 true，风险是把过滤空集当作官方无数据 | A |
| 11 | `SourceYearCoverage` 有发现/抓取/解析/岗位/页数/过滤/失败/最早最晚/停止原因；`A/AcquisitionService::backfill` 依据 traversal、failed、targetJobs 决定 COMPLETE | 基础计数存在，但缺附件分母/成功率、持久 entry 级证据、生命周期/orphan 对账及官方交叉核验。完整末页不是全年无漏采证明 | A、B、E、F |
| 12 | `D/RecruitmentLifecycle.java` 八个阶段；`M/V38__recruitment_lifecycle_documents.sql` MATCHED/UNMATCHED/AMBIGUOUS；`OfficialLifecycleDocumentService` 唯一候选匹配 | 已保留未关联与歧义；缺 registration、correction、cancellation、reduction、extension、replacement、abandonment 的统一事件。`Phase2DocumentProcessor.importWorkbook` 直接 IGNORED 生命周期工作簿，未抽取名单语义 | D |
| 13 | `I/acquisition/OfficialAnnouncementFactParser.java`、`D/RecruitmentProcessFacts.java`、M/V25、V28 保存报名区间、考试与各阶段事实 | 已有报名/复审/笔试信息；需核实全导入路径，补账户需求、明确渠道和不可变延期事件，保留原截止日 | C、D |
| 14 | `OfficialAnnouncementFactParser`、`I/persistence/OfficialAnnouncementFactService.java`、V13/V16 字段证据、`D/GraduateEligibilityRule.java` | 正文融合、境外/留服/未就业/社保事实已有；补充/复审规则仍需统一参与融合，学历学位/户籍/职称/证书/政治面貌等须逐字段审计。基线年龄/工作年限首数字提取与年龄基准误用由主线另行修复 | A、E |
| 15 | `D/PolicyRule.java`、`I/persistence/PolicyRuleJpaRepository.java`、V1 policy_rule 有 jurisdiction/validity/parameters/evidence | 政策规则基础存在；缺独立 policy_source、政策版本、适用 job/recruitment 关系和政策更新采集；现在应届/社保口径仍部分依赖 parser 正则 | E |
| 16 | `career-web/.../AcquisitionApiModels.java` 输出年度、文档问题、生命周期关联数；`career-ui/src/features/updates/SourceList.tsx` 已展示相关计数 | 现有来源页面可扩展；缺六级验收、附件成功率、coverage confidence、gap/warning、未解决 job/event 与解释闭环。历史 ACCESSIBLE 也不能代替本轮验收 | A、F |
| 17 | V53 卫健委来源与 V57/V62/V68 等配置文字提出跨渠道补齐；`ListingEntryContract` 无 VERIFICATION 角色 | 有交叉官方来源但没有自动 verification-event 比对/gap 队列。儿童医院 V75 的标题限制避免误归属，却不能证明卫健委批量公告里无该院岗位 | E、F |
| 18 | `SourceYearCoverage`、连接 projector 和 batch acceptance 仅旧状态 | 六级标准尚无对应实现和逐来源证据输出；旧测试成功或连接数不能授予 Level 2—6。本次文档定义新门槛，下一步实现独立审计模型 | A、F |

## 会影响用户判断的具体问题

1. 年度 `COMPLETE` 推导过弱。基线 `AcquisitionService.java` 的年度选择以遍历完成、无失败、有目标岗位为主要条件；`NO_TARGET_RECORDS` 可以由零发现生成。应冻结旧结论作 legacy evidence，重新评估，不能迁移为新版完成。
2. 儿童医院标题过滤只是来源身份控制。V75 使综合卫健委公告不再冒充医院直发，V76 允许空增量；这两点都无法证明 2024—2026 医院无目标岗位。需要从综合公告岗位表识别实际用人主体，并记录跨来源覆盖关系。
3. 岗位与文档生命周期需分开。文档下线可能是网站迁移；岗位取消须有官方事件。旧岗位当前投影和指纹不能回答“最初招几人、后来减了几人、截止日何时改变”。
4. 资格历史分析必须使用明确年龄基准、毕业年份和规则适用期。2027 资格应届判断不能被 2026 公告或 2024 历史筛选的结果覆盖。

## 本轮可复用与必须补建的边界

复用 HTTP 白名单/节流/锁、多入口分页、原始 artifact、证据片段、提取运行、复核队列、现有 recruitment_event、job_posting 和 policy_rule。补建批次别名/多来源映射、不可变岗位版本、统一文档语义/处理状态、政策来源与适用关系、验证差异及分级审计。所有新迁移追加到主 Agent 分配的 V77 后编号，不能更改已发布迁移。

验收必须重新生成：注册表清单、三年逐源分母、批次/岗位/生命周期/附件计数、失败与待复核、policy coverage、gap/warning、逐级状态和运行 ID。本文不提供未测得的数量，也不把旧运行当作今天的服务可用性证明。
