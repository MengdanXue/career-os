# 远程资格修复适配实施计划

**Goal:** 将旧分支的资格解析修复适配到 V77 主线，避免日期和条件分支被当作硬性年龄、工作年限。

**Architecture:** Excel 与结构化页面继续共享 OfficialJobFieldMapper。缺失、歧义保留原文且输出未知；明确无经验要求输出 0。抽取管道不得将报名截止日当作年龄基准日。未知值在资格判定中必须产生待核实结果。

**Tech Stack:** Java 21、JUnit 5、Maven、现有 Spring 持久化层。

**Spec:** ../specs/2026-08-27-hangzhou-source-completion-design.md 及用户补充的六级采集验收要求。

## 执行约束

- 保留 codex/phase4b-career-workbench 的杭州采集与 V1—V77 迁移。
- 不直接合并旧分支的 V6/V7/V8，不覆盖本地新增字段和导入路径。
- 不把本轮修复宣称为全部历史采集完成；旧数据需证据驱动重解析后才能重新判定。

## 1. 共享解析器

- [x] 在 OfficialJobFieldMapperTest 中用出生日期、年龄区间、多学历上限、未满边界、日期及最低工作年限复现误读。
- [x] 执行 `mvn -pl career-infrastructure -am -Dtest=OfficialJobFieldMapperTest -Dsurefire.failIfNoSpecifiedTests=false test` 确认失败原因。
- [x] 在 OfficialJobFieldMapper 中实现明确语义提取，歧义返回 null；Excel 保留行级警告和原文。
- [x] 回归 Excel 与结构化页面共享映射测试。

## 2. 年龄基准与未知资格

- [x] DefaultJobUpsertServiceTest 验证只有报名截止日时年龄基准为空。
- [x] 修改 DefaultJobUpsertService 的抽取导入映射。
- [x] EligibilityEvaluator 将未知年龄、未知经验条件判为 UNCERTAIN，明确经验 0 保持无年限限制。
- [x] 对空值及明确条件运行资格测试，避免模糊要求被放行为合格。

## 3. 验收与集成

- [x] 适配远程 CI，验证非预期跳过必须失败。
- [x] 更新原杭州设计和计划，逐项对照 18 条补充要求及六级完成定义。
- [x] 运行受影响模块测试，检查新增失败与跳过原因。
- [ ] 审核 diff，提交并推送已授权私有分支；报告仍需完成的历史重解析与覆盖审计。

## 4. 本轮独立审查补充的缓存修复

这些是保证上述资格修复应用到旧数据的必要步骤，不改变六级历史覆盖的未完成状态。

- [x] 多行年龄/经验、经验优先、明确工作年限不限回归。新增的有限年龄与不限并列、不限与中文经验分支也须输出未知。Mapper/Excel 本轮 98 项通过，独立复审关闭两项分支遗漏。
- [x] 网页导入保留真实表格行号、资格原文换行和经验字段；warnings → ProcessingIssue → ArtifactImportFailure。实际行号未知时保持未知，禁止用过滤后序号冒充来源行号。
- [x] Phase2DocumentProcessor 升级 v15；真实 AcquisitionService/processor/hospital 导入路径验证旧 v14 相同内容与缓存 304 触发一次重解析。本组 33 项通过，底层存储接口模拟，尚不能替代 PostgreSQL 全量测试。
- [x] DecisionIntelligenceService 缓存身份包含 EligibilityEvaluator.VERSION。种入旧 ELIGIBLE 快照的回归确认 current 不返回它，assess 产生 UNCERTAIN 并幂等复用新结果；组合版本满足既有 VARCHAR(80)。
- [x] ExtractionService 增加明确的 `extraction-projection-v2`。旧自动 VERIFIED 通用 HTML/PDF 不得因为内层缓存继续保留错误年龄基准；所有有关联 reviewId 的旧记录及待审/拒绝记录保持原结果，不自动写入或重抽取。
- [x] 通用 HTML/PDF 各覆盖相同 200 和缓存 304，贯通真实 AcquisitionService → Phase2 → ExtractionService → DefaultJobUpsertService。检查旧错误年龄基准清除及再次运行不重复写入。
- [x] 对上述缓存变更做独立专项复审，全部问题关闭；最终专项 81 项通过、无跳过。人工 CONFIRM/CORRECT 后 VERIFIED 的记录另有红绿回归，确保保留人工修正人数等事实。
- [x] 执行完整 clean verify 和跳过门禁。2026-09-07 13:33:51 UTC 完成，716 项 Java 用例中 712 执行、仅 4 项显式外网 opt-in 跳过；46 项前端通过，5 份本机原始样本用例均执行。门禁及其 10 项测试通过。

## 5. 运行证据与验收边界

Docker 和数据库恢复及备份证据见 `docs/audits/2026-09-07-runtime-recovery.md`。原数据库 V77、全部 37 张 public 基表已经通过隔离 SQL 恢复后的行数及内容摘要比较；这不代表代码测试或历史覆盖完成。

本机五份可选原始测试样本均位于仓库相邻的 `output/career-os-samples/raw`，本地完整验证应执行全部五项。GitHub 干净 checkout 缺少这些外部文件时，才使用既有逐用例白名单。四项真实外网 smoke 是显式 opt-in；Docker 缺失、错误或新跳过原因均不得放行。

人工结果保留边界：旧人工确认/修正数据不因版本升级自动重抽取；若其中存在历史投影错误，需要有明确依据的复核或迁移。本次只读检查，本地 3 条已处理且 VERIFIED 的复核所关联 8 条岗位均已有空 age_reference_date，未观察到此次要清除的截止日年龄基准错误。此观测不代表这些岗位身份或资格已通过后续六级审计。

全量命令：`mvn -B -ntp clean verify`；门禁测试：`python -m unittest discover -s .github/scripts -p 'test_*.py' -v`；报告门禁：`python .github/scripts/check_skipped_tests.py`。验证日志须使用本轮新文件名，不能用交接前的日志替代。

本轮日志：`.run/full-verify-recovered-20260907.log`、`.run/skip-gate-recovered-20260907.log`、`.run/skip-gate-tests-20260907.log`。真实 PostgreSQL 集成未跳过；此前 Docker 缺失日志不作为本次证据。私有分支提交、远程 CI 和 Phase A—F 实际覆盖实施继续单独跟踪。
