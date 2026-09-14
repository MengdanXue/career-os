# Stage 1 修复交付记录（2026-09-14）

## 已验收代码

- 私有仓库：`career-os`
- 分支：`codex/stage1-release-gates-20260914`
- 代码提交：`c0054c4e6e71c6e3f9048e672ccb256c0c88f539`
- 标题：`fix(stage1): close deterministic confirmation and watchlist recovery gaps`
- 远端核对：该分支 ref 精确指向上述完整 SHA；未使用 force push。`main` 当前为另一 SHA `3da44c9744f7f0fdcff05431be800b464f9bb8e5`。

## 本地证据

- Java reactor verify：`.run/reactor-verify-projection-20260914.log`；`1025` cases，`1018` executed，`0` failures，`0` errors，`7` skips；构建与 Spring Boot repackage 成功。
- 最终跳过门：`.run/skip-gate-final-20260914.log`；报告门通过，同样核实为 `1025 / 1018 / 7`。
- 前端：`.run/ui-final-stage1-20260914.log`；Vitest `14` files、`97/97` tests passed，typecheck 通过。此前 Vite production build 已成功，最终 Maven repackage 使用该静态产物。
- 真实浏览器闭环：`.run/e2e-isolated/20260914012911-fbbb54ec/browser-evidence/report.json`；`15/15` checkpoints，`outcome=PASS`。该运行使用的打包 JAR SHA256：`b40cea6f13aa0f06a362a78077b97e0d8b371c473e2a41c41a6737911fc24b63`。
- 定向 JPA/领域回归：`.run/graduate-projection-green-20260914.log`；`107/107` green。

## 跳过项与放行条件

四项官方 live smoke 因未设置 `CAREER_OS_LIVE_SMOKE` 跳过：Government SOE、Tonglu、UCAS Hangzhou、Westlake；放行条件是显式设置该变量后，在允许访问官方网络的环境执行并保留结果。

三项历史原始样本保持条件跳过：`06-hdu-2026-second-plan.xlsx` 与 `03-hz-unified-2025-plan.xls` 本机不存在；`09-hz-capital-recruiting.html` 当前内容哈希与历史 README 不一致。已跟踪并实际执行的 PDF08、HTML10 不替代这三项证据。

## CI（只针对已验收代码 SHA）

- Run：`34801253418`（workflow `CI`，event `push`）
- URL：<https://github.com/career-os/actions/runs/34801253418>
- `head_sha`：`c0054c4e6e71c6e3f9048e672ccb256c0c88f539`
- 结果：`completed / success`（主 Java+数据库+前端步骤、报告门、Docker/Testcontainers、Mockito agent、跳过/失败拒绝步骤均成功）。
- 旧 `main` 的绿色 run 未作为本轮代码证据。

## 未关闭问题与边界

1. 原连接资源问题仍未解决：Postgres fingerprint lock 同时服务 extraction 与 decision-input，模型调用仍在事务/ advisory lock 内；不得宣称连接架构已修复。
2. 尚未实现/验证 task-level 跨内部评估/重试共享预算；当前仅有注册工具调用预算及各 HTTP 路径的局部上限。
3. 历史模型 validation failure 的 `review_item` 持久化尚未实现。
4. 列表排序遇到部分评估失败时仍可能整体失败；watchlist 降级不覆盖该路径。
5. 原“1–3 个最小问题选择”优化未验证。
6. 模型自主动态选择仍关闭、未验证；现有 scripted planner/parser 测试不构成模型证据。
7. 上述三项官方原始样本未验证；当前 not-due 查询是否混合历史岗位、声明时序/通知范围也未验证。

动态规划器代码保留但入口仍关闭；未修改原用户数据、未操作 Fabric/8010、未启动下一阶段或架构重构。`scripts/start-career-os.ps1` 的既有未提交删除保持原样，未进入本轮提交。

## 状态

- 合并：否
- 部署：否
- 下一阶段：未开始；等待用户确认剩余门槛处理范围。

