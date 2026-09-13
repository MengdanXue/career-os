# 闭环验收：真实浏览器 + 真实后端 + 真实数据库

日期：2026-09-13。提交：见本文件所在提交的父提交 `d40162d` 及本提交。

## 为什么必须打真实栈

下面每一条在单元测试里都是绿的。问题只在连起来时出现：

- **连续确认**：第二个待确认问题必定失败（`PROFILE_VERSION_CHANGED`）。会话记的还是首次查询时的
  资料版本，而用户自己的上一次确认已经把版本推高了。每条确认单独测都对。
- **关注清单**：界面里没有任何"关注"入口，空清单又直接不渲染。接口齐全，功能不可达。
- **配置账号登录**：`{bcrypt}` 前缀配裸 `BCryptPasswordEncoder`，凡是按文档配了账号的部署一律 401。
  既有安全用例全用 `@WithMockUser`，整个跳过口令编码器。

## 环境

- 后端：`career-web-0.1.0-SNAPSHOT.jar`，端口 18080，HTTP Basic
- 数据库：本机 PostgreSQL 16.13，库 `career_os`，Flyway 88 个迁移全部应用
- 前端：`npm run build` 产物由后端从 `static/` 提供
- 浏览器：Chromium（Playwright 驱动，非无头 shell）
- 数据：候选人 1 名；岗位 2 个，均 `VERIFIED`/`INCLUDED`；其一限中共党员，其一限女性

## 浏览器验收（`e2e/closed-loop.mjs`，10/10 通过）

页面加载 · 答案含确定性事实块 · 未把指数说成录取概率 · 渲染待确认问题 ·
先声明这是本人声明 · 确认 #1 已记录 · 确认 #2 未被版本检查挡下 ·
可以从结果行加入关注 · 刷新后关注清单可见 · 无 JS 运行时错误

浏览器操作之后的真实库状态（不是断言文案，是落库结果）：

```
fact: GENDER = CONFIRMED src=USER_CONFIRMED
fact: POLITICAL_AFFILIATION = CONFIRMED src=USER_CONFIRMED
profile: political=CPC_MEMBER gender=FEMALE
ledger: GENDER FEMALE stage=RECOMPUTED
ledger: POLITICAL_AFFILIATION CPC_MEMBER stage=RECOMPUTED
watch: c0000000-... lastSeen=NEEDS_CONFIRMATION      ← 基准在关注时就建立了
```

## 接口级验收（同一实例，curl）

```
两次连续确认                    => RECORDED, RECORDED
会话 GET（模拟刷新）            => 两条都标记 answered=true
换个候选人读同一会话            => 404 SESSION_NOT_FOUND
同一幂等钥匙重放                => ALREADY_RECORDED，没有第二次写入
关注（带基准）                  => changed=false, baselineMissing=false
岗位结论变为 INELIGIBLE         => changed=true，待确认 -> 不可报
再读一次清单                    => 变化标记仍在（刷新不会抹掉）
确认已看过                      => 标记清除
```

## 失败恢复：真实故障注入（后补，已完成）

把一个岗位的 `job_family` 改成非法枚举值，使重算在真实实例上必定失败。

**注入前（修复前的行为）**：

```
POST /profile-confirmations  => HTTP 500  UnexpectedRollbackException
fact POLITICAL = UNCONFIRMED      ← 回答丢了
profile political = UNKNOWN       ← 资料没写
ledger rows = 0                   ← 台账也没了
```

根因：控制器把写入和重算包在一个 `@Transactional` 里。重算内部的评估失败把整个事务标成
rollback-only，服务层 catch 住异常、返回"已记录但未重算"之后，提交阶段仍然整体回滚。
**"回答不会丢"这条保证在生产里是假的。** 单元测试照不出来——假评估器只是抛异常，没有事务。

**修复**：两段式。第一段在事务里写资料与台账并提交；第二段在事务之外重算。

**注入后（修复后的行为）**：

```
POST /profile-confirmations  => HTTP 200  RECORDED_RECOMPUTE_DEFERRED
   "已按你本人的声明记录…岗位结论尚未重算完成，稍后重试即可，回答不会重复记录。"
fact   = CONFIRMED
profile = CPC_MEMBER
ledger  = 1 stage=WRITTEN          ← 停在可恢复的中间态
```

**修好岗位后用同一把幂等钥匙重试**：

```
=> HTTP 200  RECORDED   "上次的回答已经记录，这次补完了岗位结论的重算。"
ledger = 1 stage=RECOMPUTED
versions: before=profile-fail-3 after=profile-0800e999-…
current profile version = profile-0800e999-…   ← 与 after 相同，没有第二次写入
```

结构侧由 `ConfirmationTransactionBoundaryTest` 守住：控制器方法与类上都不得有 `@Transactional`。

## 顺带发现（未修，记录在案）

一个岗位无法评估时，**整个排序查询失败**，返回里连 `sessionId` 都没有，而不是跳过该岗位并说明。
关注清单对这种情况是降级处理的（标成读不到），排序路径不是。本轮未改，属于独立问题。

## 尚未验收

- 模型自主选工具：尚未接入。当前模型只写连接性叙述。

## 复跑方式

`e2e/closed-loop.mjs` 头部有前置条件说明。脚本会先自检"至少两条待确认事项"，
不满足时以退出码 2 明确报出——起点不对时的失败看起来和产品缺陷一模一样，这一点已经浪费过一次排查。
