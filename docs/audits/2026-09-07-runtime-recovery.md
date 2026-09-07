# Docker 与数据库恢复证据

2026-09-07，本次恢复先读取 NEXT_TASK.md 与 Git 状态，保留全部未提交变更。本文记录运行条件与备份验证，不授予历史采集完成级别。

## 故障和恢复

Docker Desktop 4.88.1 在初始化 AF_UNIX socket 时退出。首先失败于 `LocalAppData/Docker/run/sailor-ingest.sock`；保留旧运行目录并创建新目录后，启动越过此处，随后失败于 `LocalAppData/docker-secrets-engine/engine.sock`。旧目录含零字节 socket，具有 Windows Encrypted 属性；普通重命名被拒绝。新建的独立探测目录也继承 Encrypted 属性且无法重命名，故未继续删除文件或改变加密权限。

在完成离线磁盘备份后，仅对 Docker Desktop 子进程设置新的 `LOCALAPPDATA=D:/DockerDesktop/recovery-runtime-20260907`。原 Roaming 配置仍选择 `CustomWslDistroDir=D:\DockerDesktop\wsl`，原数据库磁盘和卷保留。`docker info` 返回 Engine 29.7.2，原 `career-os-postgres-1` 自动恢复为 healthy，端口仍为 55432，挂载卷仍为 `career-os_career_os_postgres`（创建日期 2026-08-20）。其他项目的容器与卷仍存在，未执行卸载、重装、恢复出厂、volume prune 或清空数据库。

这是本次运行的缓存目录恢复方案。普通 Docker 快捷方式仍可能使用旧缓存；可复用本地 `.run/start-docker-recovery.ps1` 启动，脚本检查已验证备份和原磁盘配置，不修改全局环境变量。未证明 Windows 原加密目录问题已永久消除。Docker 自身可能在新缓存中显示登录提示；本项目数据库和 Testcontainers 运行不以重新登录为完成前提。

排错参考：[Docker 官方项目的同类 socket 故障报告](https://github.com/docker/desktop-feedback/issues/554)。备份方法参照 [Docker 备份恢复文档](https://docs.docker.com/desktop/settings-and-maintenance/backup-and-restore/)。外部故障报告仅用于定位；本机恢复结论来自上述实际引擎和卷检查。

## 备份验证

备份目录：`D:/DockerBackups/career-os-recovery-20260907`。不提交数据库、用户资料或原始采集文件到 Git。

| 对象 | 大小/数量 | 验证 |
| --- | --- | --- |
| docker_data.vhdx | 78,613,839,872 字节 | Docker/WSL 停止期间复制；原文件与副本 SHA-256 一致 |
| ext4.vhdx | 100,663,296 字节 | 原文件与副本 SHA-256 一致 |
| 原始 artifacts | 4,107 个文件，182,421,340 字节 | 每个文件原件/副本 SHA-256 一致 |
| career-os.dump | PostgreSQL 16 custom-format 全库备份 | 隔离容器 pg_restore --exit-on-error 成功；所有 37 张 public 基表行数与排序后的逐行内容摘要一致 |

磁盘校验完成于 2026-09-07 13:17 UTC。详细摘要、长度和时间保存在 `manifest.json`、`artifact-manifest.json`；SQL 恢复比较保存在两个 `*-table-digests.txt` 文件。隔离恢复容器只使用临时内存存储且不开放宿主端口；验证后核对容器 ID 与专属标签，停止并移除该验证容器。

恢复时数据库观测：job_posting 24,283；recruitment_event 1,719；acquired_document 3,754；source_year_coverage 116。Flyway 最新成功版本 V77。这些是原数据库表的行数，尚不是跨来源去重岗位数、新版批次数或六级覆盖率。

## 后续验收

恢复数据库不等于测试通过。资格修复须完成缓存重解析和旧决策失效回归，随后执行全量 `mvn -B -ntp clean verify`、CI 跳过检查及真实采集验收。所有未完成事项仍按当前 Phase A—F 计划推进。
