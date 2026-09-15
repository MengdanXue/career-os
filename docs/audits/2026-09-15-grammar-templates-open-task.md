# 剩余七条原反例：完整语法、有限模板、会话把任务存下来

日期：2026-09-15
基线：`dc2edc4`（其有效修复全部保留）
范围：19 条边界用例里仍未关闭的 7 条
不在范围：目录、预算、并发、事务恢复；不扩新框架

## 做法

三组各自先写成红、再改实现。原反例按原样写进用例，不换措辞。

## G1：非协议行被忽略、BASIS -1 被读成正数

### 修复前

```
[ERROR] Tests run: 26, Failures: 2 -- ModelPlannerTest
[ERROR]   ModelPlannerTest.aLineThatIsNotPartOfTheProtocolVoidsTheTurn:126
[ERROR]   ModelPlannerTest.aNegativeBasisIndexIsNotReadAsAPositiveOne:151
```

两条都是"只按自己认得的那部分去理解"：

| 模型输出 | 修复前 | 修复后 |
|---|---|---|
| 闲话一行 + `TOOL search_jobs location=杭州` | 闲话被忽略，照常执行 | 整段作废 |
| `TOOL …` + `另外这次只看事业编。` | 后一句被忽略，结果比用户要的宽 | 整段作废 |
| `BASIS -1` | 把数字挑出来 → 第 1 条 | 整段作废 |
| `BASIS 1, -2` / `BASIS 1.5` | 同上 | 整段作废 |

**按完整语法处理，没有加字符串特判。** 每一行必须落在一条产生式上：
动作行（TOOL／ASK／FINISH）、WHY、BASIS、空行，此外一律作废。
BASIS 的取值也写成语法——`none` 这个词本身，或一串正整数；
负号和小数点根本没进过语法，读不出来就是读不出来，不会读成别的东西。
上一轮那个 `contains("无")` 的特判随之删掉了。

### 修复后

```
[INFO] Tests run: 30, Failures: 0 -- ModelPlannerTest
```

## G2：五条原句，改用有限模板而不是再加词表

### 修复前

```
[ERROR] AgentExecutorTest.theReviewPackSentencesCannotReachTheUser:572
        这句话到了用户面前：FINISH 你这条线基本没什么硬门槛挡着。
```

复核包点名的四类，各取原句（资格两条、材料、概率、伪澄清）：

| 类 | 原句 |
|---|---|
| 资格 | 你这条线基本没什么硬门槛挡着。 |
| 资格 | 这几个的报考条件你都对得上。 |
| 材料 | 你把学历证明补齐就能报名了。 |
| 概率 | 以你的条件，进面基本没问题。 |
| 伪澄清 | 这批里有几个明显更稳妥，要我先讲哪个？ |

这五句一个数字、一个分层标签都没有。上一轮新加的推荐词表拦的是"适合""建议报考"
那几种写法，换个说法就绕过去——**往词表里加词永远慢模型一步：能说的句子是无穷的，
词表是有限的。**

### 修复后：用户可见的话收束成有限模板

模型现在只能给**模板名 + 结构化引用**，句子由程序渲染：

- 收尾五条：`RANKED_LISTING`、`NOTHING_IN_SCOPE`、`SINGLE_JOB`、`PENDING_FIRST`、`WATCHLIST_STATE`
- 追问五条：`WHICH_LOCATION`、`BROADEN_SCOPE`、`WHICH_ORDINAL`、`CONFIRM_FACT`（带 `fact=` 槽）、`RETRY_LATER`
- 唯一的槽位取值只能来自封闭枚举（`CandidateFactKey`），认不出来就作废——
  把一个看不懂的字段名渲染给用户，比不渲染更糟

那五条不是被检测出来的，是**写不出来的**。依据绑定（BASIS）一条没动，
模板不是拿来代替它的：模板管"这句话能不能说"，BASIS 管"凭什么这么说"。

**代价明说**：模型少了措辞上的自由，答复读起来更像固定句式。这一版认为值得——
用户看到的每一句判断都必须是程序算出来的。

三道守护：每条模板渲染出来的句子都要过叙述校验器；模板名不在清单里就没有这句话；
槽位取值超出封闭集也没有这句话。

### 顺带被守护用例抓到的一处

`SINGLE_JOB` 原文写的是"下面是**这一个**岗位的情况"，"一个"命中了中文数字规则，
`everyTemplateRendersTextThatPassesTheNarrativeRules` 当场红。改成"这个岗位"。
这正是留这道校验的理由——改模板时当场红，而不是等用户看到。

## G3：轨迹错位、追问时不保存任务

### 修复前

```
[ERROR] Tests run: 15, Failures: 2 -- AgentRunApiTest
[ERROR]   AgentRunApiTest.aRejectedCallDoesNotMisalignTheSearchArguments:317
[ERROR]   AgentRunApiTest.aRunThatEndsInAQuestionSavesTheOpenTaskAndTheQuestion:337
```

**错位**：`searchArguments` 靠"数到第几个被接受的"去对位，可被拒的调用同样会留下一条
失败观察。第一步请求了不存在的工具、第二步查询成功，对位之后读到的是第一步那条失败观察，
于是这一轮被判成"没查出新列表"——用户明明看到了新列表，下一句"第二个"却指回上一轮。
修法是让执行器在轨迹里记下 `observationIndex`，不再事后推算。

**追问时不保存**：一轮以追问收场时，列表没变，但"还欠着什么"和"问过什么"也没存。
用户刷新之后只答一句"余杭"，系统既不知道他要办什么，也不知道自己问过什么。
新增 `open_task` / `pending_question` 两列（V89）与 `rememberAsk`；
拿到结果的那一轮会把它们清空，否则已经办完的事会被下一轮当成还欠着的。

### 真机跑出来的第三处

第一轮就以追问收场时压根没有会话可存——最先问出去的那一句永远接不回来。
补了"第一轮追问也开一轮会话"，并先写成红：

```
[ERROR] AgentRunApiTest.aFirstRoundThatEndsInAQuestionOpensASessionSoItCanBeResumed:359
        Expected a non-empty value at JSON path "$.sessionId" but found: null
```

### 修复后

```
[INFO] Tests run: 16, Failures: 0 -- AgentRunApiTest
```

## 多轮脚本：真正的追问 → 刷新 → 只答余杭 → 继续原任务

`e2e/multi-round.mjs` 全程从页面的动态面板调 `/agent-runs`：

```
PASS | 第一轮以追问收场 | 它转向追问你
PASS | 追问是程序渲染的固定句式 | 你想看哪个城市或区县的岗位？
PASS | 追问这一轮没有列岗位
PASS | 刷新后摆回了那句追问 | 上次问你：你想看哪个城市或区县的岗位？ 为了：有什么合适的
PASS | 刷新后也说明了这是在办哪件事
PASS | 只答"余杭"之后拿到了新列表 | 2 个
PASS | "第二个"只讲一个岗位 | …0003
PASS | "第二个"指向刚展示的新列表的第二个 | 期望 …0003，实得 …0003
PASS | "第二个"不是新列表的第一个 | 新列表第一个是 …0002
PASS | 无 JS 运行时错误
=== 10/10 通过 ===
```

岗位身份取自页面上"查看档案"链接里的 id，不看接口内部字段，也不用列表长度代替身份。

## 一条原用例被新机制取代，说明在这里

原来有一条"多行收尾叙述要完整保留"，防的是限制条件单独成行时被悄悄删掉。
现在收尾没有自由叙述了：模型给的是模板名，截不断也改不了措辞；模板名后面多写一行，
整段直接作废。这比"保留下来"更严——以前是保住，现在是根本进不来。
用例改名为 `aClosingCarriesATemplateNameRatherThanProse`，并在注释里写清了这层关系。

## 验收结果

| 模块 | 结果 |
|---|---|
| career-domain | 194 通过 |
| career-application | 322 通过 |
| career-infrastructure | 405 通过（52 跳过，需要 Docker） |
| career-web | 120 通过（22 跳过） |
| career-ui | 81 通过，tsc 无错 |

真实浏览器 + 真实后端 + 真实 PostgreSQL：

- `e2e/closed-loop.mjs` **16/16**
- `e2e/multi-round.mjs` **10/10**
- `e2e/recompute-retry.mjs` **8/8**

## 出网阻塞（单列）

冻结小样本仍然跑不了：egress 代理对 `api.deepseek.com:443` 与 `api.openai.com:443` 都返回
`connect_rejected`。策略拒绝不重试、不绕行，也不索要聊天里的 key。

`ModelPlannerLiveSampleTest` 的第四条样本随这一轮改了判据：原来量的是"模型写的收尾有多少违规"，
现在句子由程序渲染，那个比例恒为零，量的已经不是模型的行为。换成**模型选的模板要和工具结果对得上**
——一个岗位都没查到时不能选"下面是这个范围内的岗位"。这仍然是还由模型决定的事。

## 仍然没有做到的事

- **还没有证明模型会依据不同工具结果选择不同下一步。** 出网被策略挡住。
- **没有任何形式的按候选人授权**：通过认证的操作员仍可对任何 candidateId 操作。
- 一个岗位算不出来仍会让整次排名查询失败（关注清单会降级，排名不会）。
