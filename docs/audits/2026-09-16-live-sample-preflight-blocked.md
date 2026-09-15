# 联网样本的起跑前核对：出网仍被拒 + 冻结版本跑不起来（2026-09-15）

本轮不开发功能。按要求只做一件事：在跑 `ModelPlannerLiveSampleTest` 之前，
核对完整 SHA、工作区、依赖和模型配置。核对查出两件事，一件是预期的阻塞，
另一件不是——冻结的那个版本**根本跑不了这四条样本**。

---

## 一、核对结果

### SHA 与工作区

```
HEAD                                   6dc7f69810307947b086d5c731b62d0f86168e2b
main                                   6dc7f69810307947b086d5c731b62d0f86168e2b
claude/loving-hopper-mggl1c            6dc7f69810307947b086d5c731b62d0f86168e2b
origin/main                            6dc7f69810307947b086d5c731b62d0f86168e2b
origin/claude/loving-hopper-mggl1c     6dc7f69810307947b086d5c731b62d0f86168e2b
```

`git status --porcelain` 为空；`git diff origin/main HEAD` 为空。四个引用同一个完整 SHA，
与用户指定的冻结版本逐字符一致。

### 依赖与剖面

`llm-integration` 剖面在根 `pom.xml`：把 `surefire.excludedGroups` 从
`llm-integration,acquisition-live` 换成只剩 `acquisition-live`，
也就是<b>只有加了这个 `-P` 才会选中这四条样本</b>。默认构建永远不跑它们。

离线依赖齐全，`mvn -o -P llm-integration -pl career-web -am test -Dtest=ModelPlannerLiveSampleTest`
能走到执行阶段——但见下一节。

### 模型配置

本环境：`OPENAI_API_KEY`、`CAREER_OS_AI_BASE_URL`、`CAREER_OS_AI_MODEL` **三个都未设置**。
按约定凭据只在授权环境配置，这里不设、不索要、不进仓库。
`git grep -nE "sk-[A-Za-z0-9]{16,}"` 全仓库无命中。

### 出网（各探一次，不重试）

```
api.deepseek.com     curl: (56) CONNECT tunnel failed, response 403
api.openai.com       curl: (56) CONNECT tunnel failed, response 403
```

代理自报的近期失败：

```
connect_rejected  gateway answered 403 to CONNECT (policy denial or upstream failure)  api.deepseek.com:443
connect_rejected  gateway answered 403 to CONNECT (policy denial or upstream failure)  api.openai.com:443
```

**记为阻塞**。没有绕行，没有反复探测，没有索要 key。本环境跑不了这一步。

---

## 二、核对查出来的问题：冻结版本跑不了这四条样本

这不是出网的问题，**有没有 key 都一样**。

```
Tests run: 5, Failures: 0, Errors: 5, Skipped: 0
java.lang.ExceptionInInitializerError
Caused by: java.lang.NullPointerException: watchlist
    at com.careeros.application.agent.ReadOnlyTools.watchlist(ReadOnlyTools.java:178)
    at com.careeros.ModelPlannerLiveSampleTest.<clinit>(ModelPlannerLiveSampleTest.java:82)
```

原文见 `docs/audits/evidence/2026-09-15-live-sample-cannot-initialise-at-6dc7f69.txt`，
是在 6dc7f69 上跑出来的。

静态字段里写的是 `ReadOnlyTools.watchlist(null, CLOCK)`，而那个方法对 null 直接
`Objects.requireNonNull` 抛 NPE。于是**整个类的静态初始化就炸了**：四条样本一条都到不了
`assumeTrue`，全部报 `ExceptionInInitializerError`。

带到授权环境去，拿到的会是 5 个 error，而不是四条样本的运行结果。

**为什么一直没人发现。** 这个类带着 `@Tag("llm-integration")`，默认被 surefire 排除。
"默认不跑"等于"从没验过"——上一轮离线全绿、CI 全绿，都跟它没有关系。
这跟之前那次 `MigrationIntegrationTest`（本地没 Docker 被跳过，CI 才红）是同一类事：
**被跳过的用例不提供任何保证**。

### 修法

只动测试固定装置，不碰生产代码，不碰四条样本的任何判据：
把 `null` 换成一个空实现的 `JobWatchlistService`。

顺带补一条**默认会跑**的守护 `ModelPlannerLiveSampleFixtureTest`：不需要 key、不需要出网，
只把那个类加载起来，并核对它的工具目录仍然是线上那四个
（目录少一个工具，发给模型的那份就和线上不一样，样本量的就不再是线上那套工具面）。
把 `null` 改回去验过它是红的：2 跑 1 失败 1 错误。

---

## 三、跑之前必须知道的一件事：跳过 ≠ 通过

修好之后，在**没有 key** 的本环境跑同一条命令：

```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

`assumeTrue(OPENAI_API_KEY != null)` 让四条样本**全部跳过**，maven 照样报 BUILD SUCCESS。
这正是要提防的那种假通过。所以在授权环境里，判断"确实跑了"的唯一依据是这一行：

```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

`Skipped` 不是 0，就是没跑，不管 BUILD 是什么。

### 授权环境的跑法与要保存的东西

```
OPENAI_API_KEY=…                 # 只在授权环境配置
CAREER_OS_AI_BASE_URL=…          # OpenAI 兼容端点
CAREER_OS_AI_MODEL=…             # 温度由测试置零，便于反复比对
mvn -P llm-integration -pl career-web -am test -Dtest=ModelPlannerLiveSampleTest
```

四条样本各自量的东西：

| 样本 | 判据 |
| --- | --- |
| `theModelTakesADifferentNextStepWhenTheToolResultDiffers` | 同一问题同一目录，只有工具结果不同，下一步必须不同 |
| `theModelCorrectsAnIllegalArgumentInsteadOfRepeatingIt` | 非法参数被拒后改用合法取值，而不是再报一遍 |
| `theModelConvergesWhenTheBudgetIsNearlyGone` | 预算只剩一次时不再要求调工具 |
| `theModelPicksAClosingTemplateThatMatchesTheToolResult` | 一个岗位都没查到时不选讲列表的模板 |

测试里的 `report(...)` 会把每一步的**实际动作、参数、模板名和 BASIS** 打进标准输出，
所以保存 surefire 报告之外还要保存完整的构建输出，两者缺一不可：
报告只说通过与否，动作和依据在输出里。

**两个输出不同不等于模型选对了。** 第一条样本除了比对"不同"，还各自跑 `assertUsable`：
工具要在注册表里、参数要过得了工具自己的校验、收尾要点名依据且点到成功的观察。
只看"两个结果不一样"会把随机噪声当成推理——温度已置零正是为了排除这一项。

样本失败就保留失败，不改判据、不挑重跑结果。

---

## 四、本轮验收

| 模块 | 结果 |
| --- | --- |
| career-domain | 194 通过 |
| career-application | 360 通过 |
| career-infrastructure | 405 通过，52 跳过 |
| career-web | 128 通过，22 跳过（含新增守护 2 条） |
| career-ui (vitest) | 81 通过 |

**顺带更正上一轮的一个数字。** 上一轮记录里 career-web 写的是 129，那个数是错的：
它把 `target/surefire-reports` 里上一次 `-Dtest=…` 单跑留下的陈旧报告一起数了进去。
清掉报告目录重跑，冻结版本 6dc7f69 的 career-web 在默认剖面下是 **126 通过、22 跳过**；
本轮加了 2 条守护，所以是 128。其余模块复核过，不受影响。结论（全绿）没有变，
但统计口径必须是干净的一次完整跑，不能是目录里攒下来的东西。

浏览器脚本本轮未改动，也未重跑——本轮没有触碰前后端行为。

---

## 五、状态

- **联网样本：阻塞。** 出网策略 403 拒绝 CONNECT，本环境无法执行。不绕行、不重试、不索要 key。
- **冻结版本需要重新指定。** 6dc7f69 跑不了这四条样本；修好之后的版本才是能拿去授权环境的候选。
- 本轮不包含、继续单列：按候选人授权、原环境部署、所有场景的生产可靠性。
