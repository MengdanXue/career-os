# 参与 Career OS

欢迎提 issue 和 PR。这份文档说清三件事：怎么把项目跑起来、提交要满足什么、以及这个项目
对"测试通过"的定义——最后一条和多数项目不太一样，值得先读。

## 一、开发者证书（DCO）

本项目用 **DCO**，不用 CLA。你不需要签任何法律文件，只要在每个提交里加一行签署：

```bash
git commit -s -m "fix: ..."
```

`-s` 会自动追加：

```
Signed-off-by: 你的名字 <你的邮箱>
```

这一行的含义是 [Developer Certificate of Origin 1.1](https://developercertificate.org/)：
你声明这份代码是你写的、或你有权按本项目的许可证提交它。用你真实可联系的身份，
别用匿名或假地址。

忘了加可以补：`git commit --amend -s`，多个提交用 `git rebase --signoff`。

## 二、把它跑起来

前置：**JDK 21**、**Maven 3.9+**、**Node 20+**、**Docker**（跑数据库集成测试要用）。

```bash
mvn clean verify          # 全部 Java 模块 + 前端构建与单测
cd career-ui && npx vitest run   # 只跑前端单测
```

`career-ui` 的构建由 `frontend-maven-plugin` 挂在 `career-web` 上，所以 `mvn verify`
会连前端一起跑，不需要单独操作。

**没有 Docker 时**，Testcontainers 那批用例会被跳过，本地看起来是绿的。这正是下一节要讲的问题。

### 真实浏览器验收

`e2e/` 下三个脚本打的是真实后端 + 真实 PostgreSQL + 真实浏览器，不用替身：

```bash
E2E_USER=... E2E_PASS=... CHROME_PATH=... node e2e/closed-loop.mjs
```

它们刻意不带默认口令——验收脚本里留一个可以照抄的口令，等于把它变成默认配置。

### 模型样本（可选）

`ModelPlannerLiveSampleTest` 默认被 surefire 排除（`@Tag("llm-integration")`），
要真实模型凭据才能跑：

```bash
OPENAI_API_KEY=... CAREER_OS_AI_BASE_URL=... CAREER_OS_AI_MODEL=... \
  mvn -P llm-integration -pl career-web -am test -Dtest=ModelPlannerLiveSampleTest
```

**没有 key 时它是 `Tests run: 4, Skipped: 4` 加 BUILD SUCCESS。** 判断"确实跑了"
只看 `Skipped: 0`，不看 BUILD 结果。

## 三、这个项目对"通过"的定义

三条，都是被真实故障教出来的：

**1. 跳过的用例不提供任何保证。** CI 有一道 `check_skipped_tests.py`：只有白名单内的
跳过才放行，多出一条就让构建失败。历史上出过两次"本地全绿、CI 才红"——本地没 Docker，
迁移用例被跳过；以及一个默认被排除的测试类连静态初始化都过不去，四条样本一条也没跑过，
而没有人知道。

**2. 修 bug 先写红。** 提 PR 修复缺陷时，请先提交一个能复现它的失败用例，再提交修复。
红的输出（哪一行、期望什么、实得什么）比"我修好了"有用得多。改判据让测试变绿不算修复。

**3. 用户可见的结论必须有依据。** 岗位资格、分层、待确认项这些判断全部由确定性规则算出，
模型只负责决定查什么、问什么。给用户看的收尾和追问从封闭模板里选，句子由程序渲染，
并且要点名依据哪几条工具结果——详见 `AnswerTemplates` 和 `AgentExecutor` 的类注释。
新增模板要同时声明它的适用条件。

## 四、提交与 PR

- 提交信息用祈使句，说清**为什么**这么改，不只是改了什么。仓库里现有的提交是样例。
- 一个 PR 一件事。顺手的重构请单独开。
- 不要提交个人信息：真实姓名、生日、证件号、联系方式、本机绝对路径、任何凭据。
  种子数据和测试固定装置里的候选人是**虚构占位**，请保持虚构。
- 改了 `career-infrastructure/src/main/resources/db/migration/` 下的迁移，记得
  `MigrationIntegrationTest` 里钉着迁移条数；新增迁移要同步那个数字。
  **不要修改已发布迁移的内容**——那会改变 Flyway 校验和，已部署的数据库会启动失败。
- 种子里的 `candidate_fact_confirmation.value_fingerprint` 是事实值的 SHA-256。
  改了种子的事实值就要重算指纹，否则那项事实会静默退回"未确认"。

## 五、报告安全问题

不要开公开 issue。见 [SECURITY.md](SECURITY.md)。

## 许可

提交即表示你同意你的贡献按 [Apache License 2.0](LICENSE) 授权。
