# Phase 4B：Career OS 决策工作台

Phase 4B 把已有的采集、证据、增量差异、资格判断和 Agent API 组合成一个可直接使用的本地网页应用。目标仍然是浙江、重点杭州的半体制稳定技术岗位，不是通用招聘网站。

## 使用入口

在 Windows PowerShell 中执行：

```powershell
pwsh -NoProfile -File scripts/start-career-os.ps1
```

脚本会启动项目独立的 PostgreSQL 16、在源码有变化时重建应用、等待健康检查通过，然后打开 `http://localhost:8080/`。首次构建会安装锁定版本的前端依赖，耗时会比后续启动长。

停止应用并保留数据：

```powershell
pwsh -NoProfile -File scripts/stop-career-os.ps1
```

数据库默认映射到本机 `55432` 端口，避免占用常见的 `5432`。可用 `CAREER_OS_DB_PORT` 覆盖。停止脚本只停止当前项目 PID 文件记录且命令行指向当前项目 JAR 的 Java 进程；PostgreSQL 数据卷不会被删除。

## 页面与工作流

### 首次资料确认

用户必须先确认出生年月、学历、专业、地点和可接受用工形式。它们是年龄、学历、专业和用工性质等硬门槛的事实输入。资料保存后产生新版本，并触发相关决策数据刷新。

### 今天

“今天”不是传统仪表盘，只显示需要处理的变化：

- 临近截止岗位；
- 新增、变更和下线批次；
- 连续失败的数据源；
- 等待人工复核的公告提案。

同一岗位没有变化时不会反复提示为新增。某一汇总分区不可用时，其余分区仍显示，并明确标记部分信息暂不可用。

### 机会池

岗位按 T1、T2、T3、排除/复核分开显示，不跨层混排。岗位档案先展示硬性阻断，再展示适配、稳定和证据覆盖；未知条件统一标记为“待核实”。所有评分均附带“机会决策指数，不是录取概率”的边界说明。

### 更新岗位库

数据入口按可信度排序：

1. 浙江省、杭州市人社官方源，可重复触发增量采集；
2. 官方 XLS/XLSX 附件导入；
3. 官方 HTML/PDF 公告解析；
4. 低置信度结果进入人工复核，不直接进入岗位决策。

采集运行区分成功、部分成功和失败；导入使用内容指纹复用已有结果。人工复核支持确认、修正、驳回和补证据，并用版本号保护并发修改。

### Career OS Agent

Agent 只读取候选人资料、岗位决策和证据，不改写硬资格、评分或层级。模型默认关闭，此时仍由确定性 Java 规则返回受控答案；即使启用模型，模型也只负责表达，不成为决策者。

## 验证

前端类型与组件测试：

```powershell
Set-Location career-ui
npm run verify
```

本地运行契约与真实启动：

```powershell
pwsh -NoProfile -File scripts/Test-LocalRuntime.ps1
pwsh -NoProfile -File scripts/Test-LocalRuntime.ps1 -Smoke
```

应用已运行时，可执行真实 Chromium 验收。它会检查首次确认、主导航、更新工作台、无模型 Agent、390px 移动端溢出，以及浏览器控制台和 HTTP 5xx：

```powershell
python scripts/browser_acceptance.py
```

截图与报告写入 `output/playwright/`，该目录不会提交到 Git。

