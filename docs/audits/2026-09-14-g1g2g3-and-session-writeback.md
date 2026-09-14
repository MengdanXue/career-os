# G1／G2／G3 的剩余反例，与会话写回

日期：2026-09-14
基线：`e0b0b9d`（其改进全部保留）
范围：复现包中仍被放行的三组反例 + 动态会话写回 + 重写多轮验收
不在范围：目录、预算、有界并发、事务恢复等已完成项；不扩新框架

## 做法

先把仍被放行的反例写成正式回归、跑出红，再改实现。下面每组都给修复前后的实测输出。

## 修复前：八条反例全部被放行

```
[ERROR] Tests run: 315, Failures: 8, Errors: 0, Skipped: 0

[ERROR]   ModelPlannerTest.bareArgumentTextIsNotSilentlyDropped:155
[ERROR]   ModelPlannerTest.aRepeatedArgumentKeyVoidsTheTurnRatherThanKeepingTheLastValue:167
[ERROR]   ReadOnlyToolsTest.anExplicitlyEmptyValueIsRefusedRatherThanTreatedAsAbsent:161
[ERROR]   ModelPlannerTest.aClarifyingMarkerBuriedInProseDoesNotVoidNamedEvidence:185
[ERROR]   ModelPlannerTest.twoBasisLinesVoidTheTurnRatherThanTakingTheFirst:191
[ERROR]   AgentExecutorTest.aClarifyingQuestionCannotSmuggleAJudgement:494
[ERROR]   AgentExecutorTest.aStatementFollowedByAQuestionIsRefused:506
[ERROR]   AgentExecutorTest.aRecommendationIsRefusedEvenWhenItNamesEvidence:523
```

## G1：旧参数——没被解释掉的部分还在静默消失

上一轮把"取值不在允许范围"堵上了，但**根本没被解释成参数的文本**照样被丢掉：

| 模型输出 | 修复前 | 修复后 |
|---|---|---|
| `TOOL search_jobs 杭州` | 参数表为空 → 一次全国范围的查询，当成杭州的答复给用户 | 整段作废 |
| `TOOL search_jobs location:杭州` | 同上 | 整段作废 |
| `TOOL search_jobs location=杭州 而且要事业编` | 只留 location，后半句无声消失 | 整段作废 |
| `tier=T1 tier=T9` | 静默取最后一个（按 T9 查） | 整段作废 |
| `tier=`（空值） | 和"没给"一样 → 返回全量 | 失败观察，查询不发出 |

前三条比"取值不认识"更隐蔽：那种至少留下一个失败观察，这种连痕迹都没有。

实现：`arguments()` 现在要求整行由 `键=值` 记号组成，记号之间只允许空白，键不得重复；
不满足就返回 `null`，整段按"没给出下一步"处理。`reject()` 区分"没给这个键"和"给了空值"。

## G2：BASIS 的声明被一个字悄悄改写

| 模型输出 | 修复前 | 修复后 |
|---|---|---|
| `BASIS 1，无其他依据` | 含"无" → 判成纯澄清，点名的第 1 条再也不会被核对 | 自相矛盾 → 整段作废 |
| `BASIS none` + `BASIS 1` | 取第一条，第二条无声消失 | 重复声明 → 整段作废 |
| `BASIS none` | 纯澄清 | 不变（守护用例） |

判定此前靠"整串里出现过某个字"，不是"这串写的是什么"。现在只有整串等于那个词才算澄清；
既点名又带澄清词，两种读法都说得通，所以哪种都不选。

## G3：仍被放行的无依据文本

| 模型输出 | 修复前 | 修复后 |
|---|---|---|
| `ASK 这些岗位都挺适合你的，要继续看吗？` + `BASIS none` | 放行 | 拒绝 |
| `ASK 这批里有几个值得报。要我接着看吗？` + `BASIS none` | 放行 | 拒绝 |
| `FINISH 这些岗位都很适合你，建议优先准备。` + `BASIS 1` | 放行 | 拒绝 |
| `FINISH 下面按稳定性排序，先看前面几个。` + `BASIS 1` | 放行 | 不变（守护用例） |

两处漏洞：

1. **声明成纯澄清就完全不受依据检查**，于是 `BASIS none` 成了夹带结论的通道。
   现在澄清式追问额外要求：问号之前不能有一整句话（句号／叹号）。
   澄清本来就该是"你说的杭州是指市区，还是整个市？"这种形状。
2. **推荐与适配判断不在判定词清单里**。"适合你""建议优先报考"既没有数字也没有分层标签，
   可它说的正是系统从来没算过的事。`AnswerNarrativeValidator` 新增
   `RECOMMENDATION_TOKENS`，与既有清单同样是一份不完整的清单——
   真正的保证来自结构（判定只由事实块渲染），清单拦的是最像"正常措辞"的那几种。

## 修复后

```
[INFO] Tests run: 24, Failures: 0 -- ModelPlannerTest
[INFO] Tests run: 22, Failures: 0 -- ReadOnlyToolsTest
[INFO] Tests run: 32, Failures: 0 -- AgentExecutorTest
[INFO] Tests run: 315, Failures: 0, Errors: 0, Skipped: 0  （career-application 全量）
```

三条守护用例同时通过，说明不是靠"一律拒绝"过关：带空格的引号取值照常解析、
干净的 `BASIS none` 照常识别、正常的连接性叙述照常放行。

## 会话写回：只读入不算续跑

`e0b0b9d` 让执行器**读到**了上一轮，但一轮跑完什么都没存回去。于是会话永远停在第一轮：
用户在"余杭"那一轮看到的是新列表，下一句"第二个"却解析回上一轮的杭州列表。

### 修复前（接口层）

```
[ERROR] Tests run: 13, Failures: 2 -- AgentRunApiTest
[ERROR]   AgentRunApiTest.aRunSavesThisRoundsScopeListingAndPendingItems:253
[ERROR]   AgentRunApiTest.aRunWithoutASessionOpensOneAndReturnsIt:294
          Expected a non-empty value at JSON path "$.sessionId" but found: null
```

### 修复前（真实浏览器）

把写回临时去掉、重新打包、重跑多轮验收：

```
PASS | 只答"余杭"之后列表真的变了 | 杭州 3 个 → 余杭 2 个
FAIL | "第二个"只讲一个岗位
FAIL | "第二个"指向刚展示的新列表的第二个 | 期望 …0003，实得 undefined
=== 5/7 通过 ===
```

### 修复后

`AgentSessionService.rememberRun` 把这一轮的范围、列表顺序和还没答的问题存回会话。
两处刻意的取舍：范围取**这一轮实际执行成功**的那次 `search_jobs` 的参数（被拒的调用没有
产生任何结果，拿它的参数当本轮范围，下一轮就会在一个从未生效的范围上接着走）；
这一轮没有产生新列表时**不写**（用户只问了某一个岗位的截止日，清空顺序会让下一句"第二个"
无所指，而上一轮明明还在他屏幕上）。

```
=== 7/7 通过 ===
PASS | "第二个"指向刚展示的新列表的第二个 | 期望 …0003，实得 …0003
PASS | "第二个"没有指回第一轮那份列表 | 第一轮的第二个是 …0002
```

## 重写多轮验收

`e2e/multi-round.mjs` 现在**从页面上的动态面板实际调 `/agent-runs`**，不再用旧查询接口的
`sessionId` 和列表长度代替：

- 岗位身份取自页面上"查看档案"链接里的 id，即用户真正看到的东西；
- 断言是"列表内容变了"和"第二个是新列表的第二个"，不是长度——
  长度相同而内容不同的两份列表，长度比不出来；
- "没有指回第一轮那份列表"这条要求同时讲到了一个岗位，否则什么都没有时会空过。

### 顺带修掉一个验收脚本自己的缺陷

改写过程中这条一度失败，查下来是脚本的问题不是产品的问题：它只等"本次消耗预算"这段文字
出现，而那段文字上一轮就在屏幕上，等待立刻返回，读到的是正在被替换的旧内容
（`useMutation` 重跑时会先清空结果）。现在改成等结果块先消失、新的再出现。
记在这里是因为它差点让我去改一个没坏的东西。

## 验收结果

| 模块 | 结果 |
|---|---|
| career-domain | 194 通过 |
| career-application | 315 通过 |
| career-infrastructure | 405 通过（52 跳过，需要 Docker） |
| career-web | 117 通过（22 跳过） |
| career-ui | 80 通过，tsc 无错 |

真实浏览器 + 真实后端 + 真实 PostgreSQL：

- `e2e/closed-loop.mjs` **16/16**
- `e2e/multi-round.mjs` **7/7**（从动态面板实跑）
- `e2e/recompute-retry.mjs` **8/8**

## 出网阻塞（单列）

冻结小样本仍然跑不了：egress 代理对 `api.deepseek.com:443` 与 `api.openai.com:443` 都返回
`connect_rejected`。按代理说明，策略拒绝不重试、不绕行；也不再索要聊天里的 key。
`ModelPlannerLiveSampleTest` 已就绪，在能出网的机器上直接跑即可。

## 仍然没有做到的事

- **还没有证明模型会依据不同工具结果选择不同下一步。** 出网被策略挡住。
- **没有任何形式的按候选人授权**：通过认证的操作员仍可对任何 candidateId 操作。
- 一个岗位算不出来仍会让整次排名查询失败（关注清单会降级，排名不会）。
