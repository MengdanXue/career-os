# Career OS — 稳定技术岗位智能职业决策系统

**文档类型：** Master Product & Engineering Specification
**版本：** V1.0
**状态：** Baseline / 可作为 Codex 实施主规范
**目标阶段：** MVP → V1 → Agent V2
**首个真实用户场景：** 2025–2026 浙江/杭州稳定技术岗位历史库，并用于预测后续毕业年度的目标机会

---

# 1. 项目定义

Career OS 不是普通招聘爬虫，也不是简单的 AI Job Matcher。

系统目标是建立一个：

> **以历史招聘数据、资格政策、单位画像、候选人画像、岗位生命周期和招聘执行过程为基础的智能职业决策系统。**

系统回答六个核心问题：

1. 市场上有哪些值得关注的机会？
2. 用户是否具备报名/应聘资格？
3. 哪些机会最适合用户？
4. 哪些机会最容易成功？
5. 哪些机会长期最稳定、最值得进入？
6. 用户应该在什么时候、以什么材料和策略行动？

系统核心业务链路：

```text
招聘源发现
    ↓
招聘事件采集
    ↓
公告 / Excel / PDF / HTML 获取
    ↓
岗位结构化
    ↓
单位识别
    ↓
政策与资格规则解析
    ↓
候选人资格判断
    ↓
岗位匹配分析
    ↓
稳定性 / 竞争 / 未来概率分析
    ↓
机会排序
    ↓
关注 / 报名 / 笔试 / 面试管理
    ↓
结果复盘
    ↓
模型校正
```

---

# 2. 第一阶段业务目标

第一阶段只解决一个真实问题：

> 建立 **2025–2026 浙江省，重点杭州的稳定计算机技术岗位历史大库**。

主要研究：

* 历史上哪些单位持续招聘计算机人才；
* 哪些岗位接受硕士；
* 哪些岗位要求博士；
* 哪些岗位年龄为 35 / 38 / 40 / 其他；
* 哪些岗位限应届；
* 境外学历和留服认证如何处理；
* 是否要求未落实工作单位；
* 是否存在社保限制；
* 是否要求工作经历；
* 中级职称是否形成资格或竞争优势；
* 是否笔试；
* 是否专业测试；
* 是否只有面试；
* 岗位是否事业编；
* 单位及岗位是否具有长期稳定性；
* 2025、2026 是否重复招聘；
* 后续年度重新出现类似机会的概率。

---

# 3. 第一阶段候选人基准画像

系统不能把以下画像硬编码在业务代码中，但需要提供 Seed Profile 作为 MVP 测试数据。

```yaml
candidate:
  target_region_priority:
    - 浙江
    - 江苏
    - 广东
    - 福建

  primary_city:
    - 杭州

  education:
    level: 硕士
    type: 境外学历
    discipline: 计算机科学与技术

  engineering_background:
    - Java 后端
    - 企业级软件开发
    - 数据库
    - 信息系统

  research_background:
    - AI
    - GNN / 图神经网络
    - 机器学习实验

  professional_title:
    level: 中级
    name: 计算机应用
    acquisition: 评审

  target_jobs:
    - 事业单位专业技术岗
    - 政府直属信息中心
    - 数据中心
    - 数字政府技术岗
    - 科研院所工程技术岗
    - 高校正式信息化岗
    - 医院信息中心
    - 政府背景国企正式技术岗

  exclusions:
    - 劳务派遣
    - 短期项目制
    - 纯行政岗
    - 纯销售
    - 纯博士科研岗
    - 高校纯教师岗
```

候选人画像未来必须支持多用户，不允许依赖单一用户。

---

# 4. 地理范围

## P0

浙江省。

重点：

```text
杭州
├─ 市属
├─ 省属驻杭
├─ 滨江
├─ 余杭
├─ 钱塘
├─ 萧山
├─ 临平
├─ 西湖
├─ 拱墅
├─ 上城
├─ 富阳
└─ 临安
```

## P1

浙江其他重点城市：

* 宁波
* 嘉兴
* 绍兴
* 湖州

## P2

扩展：

* 江苏
* 广东
* 福建

---

# 5. 机会类型分类

统一使用 `employment_type`。

必须至少支持：

```text
PUBLIC_INSTITUTION_ESTABLISHMENT
事业单位编制

PUBLIC_INSTITUTION_FORMAL
事业单位正式聘用 / 岗位聘用

QUOTA_EMPLOYMENT
员额制

UNIVERSITY_FORMAL
高校正式技术岗位

RESEARCH_INSTITUTE_FORMAL
科研院所正式技术岗位

SOE_FORMAL
国企正式员工

GOVERNMENT_PLATFORM_FORMAL
政府背景数字化平台正式员工

CONTRACT
合同制

LABOR_DISPATCH
劳务派遣

PROJECT_BASED
项目制

UNKNOWN
尚未确认
```

重要原则：

**禁止因为“看起来像事业单位”就自动标记为事业编。**

没有证据时：

```text
employment_type = UNKNOWN
```

等待人工审核或进一步证据。

---

# 6. 纳入岗位

系统优先发现：

```text
计算机科学与技术
软件工程
人工智能
数据科学
数据工程
数据治理
数据平台
信息化
信息系统
数字政府
数字经济
科技创新服务
技术支撑
科研支撑
软件开发
Java 后端
AI 应用
政务云
网络信息中心
高校信息化
医院信息中心
工业数字化
智能制造信息化
```

---

# 7. 自动排除规则

默认排除：

```text
博士学历硬性要求
教师 / 专职教师
博士后
行政文秘
纯党建
销售
市场营销
劳务派遣
短期项目
实习
明显非技术方向
```

但必须保留原始记录。

使用：

```text
excluded = true
exclude_reason = DOCTOR_REQUIRED
```

不得物理删除。

---

# 8. 系统核心领域模型

系统不得以“一个 jobs 表”完成所有业务。

至少需要以下核心实体。

---

# 9. RecruitmentEvent — 招聘事件

表示一次独立招聘。

例如：

```text
2026杭州市部分市属事业单位统一公开招聘
```

字段：

```text
event_id
event_name
year
province
city
district
publisher
announcement_type
publish_date
registration_start
registration_end
exam_date
source_url
source_type
official_source
status
created_at
updated_at
```

`announcement_type`：

```text
UNIFIED_RECRUITMENT
SPECIAL_TECH
TALENT_INTRODUCTION
CAMPUS_RECRUITMENT
SOCIAL_RECRUITMENT
SOE_RECRUITMENT
UNIVERSITY_RECRUITMENT
HOSPITAL_RECRUITMENT
OTHER
```

---

# 10. Organization — 单位画像

字段：

```text
organization_id
organization_name
normalized_name
parent_organization
supervising_department
province
city
district

organization_type
employment_system

public_welfare_class
government_background

technical_domain

stability_score
policy_dependency_score
technical_growth_score

official_website

first_seen_year
last_seen_year
recruitment_count

human_verified
```

---

# 11. JobPosting — 岗位

字段：

```text
job_id
event_id
organization_id

job_code
job_name
job_category
job_level

recruitment_count

job_description
original_requirement_text

education_requirement
degree_requirement

major_requirement_raw
major_categories

age_limit
age_rule_raw

graduate_requirement
overseas_degree_rule
credential_rule

social_security_rule
employment_status_rule

work_experience_years
work_experience_rule

professional_title_requirement

written_exam_required
written_exam_subjects

professional_test_required
interview_required
interview_type

application_ratio

registration_deadline

employment_type

source_url
source_attachment

human_verified
```

---

# 12. PolicyRule — 政策规则

非常重要。

不能只把政策塞进岗位备注。

字段：

```text
policy_id
policy_name

jurisdiction
organization_scope

effective_from
effective_to

policy_type

raw_text
normalized_rule

source_url
source_level

human_verified
```

`policy_type`：

```text
AGE
GRADUATE_STATUS
OVERSEAS_DEGREE
CREDENTIAL
SOCIAL_SECURITY
EMPLOYMENT_STATUS
MAJOR_RECOGNITION
PROFESSIONAL_TITLE
WORK_EXPERIENCE
OTHER
```

---

# 13. CandidateProfile — 候选人画像

字段：

```text
candidate_id

birth_date

education_records
degree
major
graduation_date

overseas_degree
credential_status

employment_history
social_security_status

professional_titles

skills
research_topics
publications
projects

preferred_regions
preferred_job_types

stability_preference
income_preference
technical_growth_preference

excluded_job_types
```

---

# 14. EligibilityAssessment — 资格判断

资格判断必须和“岗位推荐”分离。

结果：

```text
ELIGIBLE
INELIGIBLE
CONDITIONAL
NEEDS_CONFIRMATION
CONFLICTING_EVIDENCE
```

> 本节原先列出 `LIKELY_ELIGIBLE` / `UNCERTAIN` / `LIKELY_INELIGIBLE`。与
> [产品需求基线](product-requirements.md) §6.1「资格不是分数」冲突，已按基线统一为上述五值。
> 逐条规则单独使用 `PASS / FAIL / CONDITIONAL / UNKNOWN / NOT_APPLICABLE`。

字段：

```text
assessment_id
candidate_id
job_id

age_status
education_status
degree_status
major_status
graduate_status
overseas_status
credential_status
social_security_status
employment_status
experience_status
professional_title_status

overall_status

blocking_reasons
uncertainty_reasons

evidence
confidence

human_verified
```

重要：

**硬资格不允许 LLM 凭感觉输出。**

---

# 15. MatchAssessment — 岗位适配

资格通过后才进入 Match。

至少输出五维评分：

```text
skill_fit
experience_fit
research_fit
career_fit
preference_fit
```

以及：

```text
overall_fit
strengths
weaknesses
resume_strategy
```

---

# 16. StabilityAssessment — 稳定性评估

不得简单：

```text
事业编 = 100
国企 = 80
```

至少综合：

```text
employment_security
funding_stability
organization_stability
policy_stability
business_volatility
layoff_risk
contract_risk
```

输出：

```text
stability_score
stability_level
evidence
```

---

# 17. CompetitionAssessment — 竞争环境

字段：

```text
recruitment_count
application_ratio
known_registration_count

major_scope_width
education_barrier
degree_barrier

organization_attractiveness
location_attractiveness

estimated_competition_level
competition_confidence
```

注意：

没有真实报名人数时必须标记：

```text
estimated = true
```

绝不能把估算伪装成实际人数。

---

# 18. JobLifecycle — 岗位生命周期

分类：

```text
REGULAR_REPLENISHMENT
稳定补充

POLICY_DRIVEN
政策驱动

EXPANSION_DRIVEN
扩张驱动

PROJECT_DRIVEN
项目驱动

ONE_OFF
一次性

UNKNOWN
```

参考：

* 历史重复；
* 单位业务；
* 政策方向；
* 招聘频率。

---

# 19. HistoricalPattern — 历史规律

需要解决：

> 2025和2026是否是“同类机会”。

不是只比较岗位名称。

必须支持 Semantic Group。

例如：

```text
杭州市西溪医院 信息中心工作人员
某医院 信息化管理
某医院 数据中心工程师
```

都可以归属：

```text
HOSPITAL_IT
```

岗位族建议：

```text
HOSPITAL_IT
UNIVERSITY_IT
GOV_INFORMATION_CENTER
GOV_DATA_CENTER
DIGITAL_GOVERNMENT
AI_RESEARCH
AI_APPLICATION
SCI_TECH_SERVICE
INDUSTRY_RESEARCH
DATA_GOVERNANCE
SOE_DATA_PLATFORM
RESEARCH_ENGINEERING
```

---

# 20. Forecast — 后续年份预测

预测对象必须分两层。

## Organization-level Forecast

例如：

```text
某单位下一年度继续招聘技术人员的概率
```

## Job-family Forecast

例如：

```text
杭州医院信息中心类岗位下一年度出现概率
```

不得直接预测：

> “西溪医院2027一定招1人。”

输出：

```text
forecast_probability
confidence
forecast_basis
supporting_history
```

---

# 21. Opportunity — 求职机会管理

这是系统从“数据库”升级成 Career OS 的关键。

状态：

```text
DISCOVERED
WATCHING
ELIGIBILITY_REVIEW
QUALIFIED
PREPARING
APPLIED
WRITTEN_EXAM
INTERVIEW
MEDICAL_CHECK
PUBLIC_NOTICE
OFFER
REJECTED
WITHDRAWN
EXPIRED
```

字段：

```text
opportunity_id
candidate_id
job_id

status
priority

deadline
next_action
next_action_date

resume_version
documents_required

notes

result
failure_reason
```

---

# 22. Source — 招聘数据源

必须单独管理。

字段：

```text
source_id
source_name
base_url

source_type
region

authority_level

crawl_mode

enabled
crawl_frequency

last_success
last_failure
failure_count
```

来源可信度：

```text
OFFICIAL_GOVERNMENT
OFFICIAL_ORGANIZATION
OFFICIAL_SOE
OFFICIAL_UNIVERSITY
AGGREGATOR
SEARCH_ENGINE
UNKNOWN
```

推荐权重：

```text
官方政府 > 单位官网 > 官方招聘平台 > 第三方汇总
```

第三方只用于：

**发现。**

资格判断尽量回溯官方来源。

---

# 23. 数据来源范围

第一阶段：

## 浙江省级

重点监控：

```text
浙江省人社体系
浙江省事业单位公开招聘
浙江省数据局及下属单位
浙江省发改委及下属单位
浙江省经信厅及下属单位
浙江省科技厅及下属单位
省属高校
省级医院
省属国企
```

## 杭州市级

```text
杭州市人社
市发改委
市经信局 / 数字经济局
市科技局
市数据相关体系
市卫健委
市属高校
市属医院
市属国企
```

## 区县

至少：

```text
滨江
余杭
钱塘
萧山
临平
```

后续扩展全部杭州区县。

---

# 24. 采集策略

不能所有网站都用浏览器 Agent。

按优先级：

```text
静态 HTML
    ↓
直接 HTTP

附件 XLS/XLSX
    ↓
Excel Parser

PDF
    ↓
PDF Parser

JS 动态页面
    ↓
Playwright

复杂语义
    ↓
LLM Extractor
```

原则：

> **能 deterministic 就不要 Agent。**

---

# 25. 数据采集 Pipeline

```text
Source Scheduler
       ↓
Page Fetcher
       ↓
Announcement Detector
       ↓
Attachment Downloader
       ↓
Content Parser
       ↓
Structured Extractor
       ↓
Normalizer
       ↓
Deduplicator
       ↓
Rule Validator
       ↓
Human Review Queue
       ↓
Database
```

---

# 26. 去重策略

不能只按 URL。

招聘公告可能被转载几十次。

Dedup Key 候选：

```text
normalized organization
+
normalized job_name
+
year
+
announcement fingerprint
```

招聘 Event 和 Job 必须分别去重。

---

# 27. 来源证据链

任何重要字段必须允许记录证据。

设计：

```text
Evidence
--------
entity_type
entity_id
field_name

source_url
source_document
source_text

source_page
source_row
confidence
```

例如：

```text
age_limit = 38

evidence:
“38周岁以下”
```

这对于政策和资格判断非常重要。

---

# 28. 人工审核机制

系统不追求 100% 无人工。

应该采用：

> Human-in-the-loop。

以下情况自动进入 Review Queue：

```text
单位性质 UNKNOWN
留学生规则不明确
社保规则冲突
专业名称无法确定
附件解析失败
不同来源出现冲突
LLM confidence < threshold
预测涉及硬资格
```

人工操作：

```text
CONFIRM
CORRECT
REJECT
NEED_MORE_EVIDENCE
```

---

# 29. Agent 系统设计

Agent 不负责一切。

Agent 主要负责“非结构化理解和决策辅助”。

---

# 30. Recruitment Extraction Agent

职责：

把非结构化公告转换为结构化对象。

输入：

```text
公告正文
附件内容
```

输出：

严格 JSON Schema。

禁止：

* 推测单位性质；
* 推测编制；
* 补充原文没有的数据。

如果未知：

```json
{
  "employmentType": "UNKNOWN"
}
```

---

# 31. Policy Agent

职责：

解析：

* 应届；
* 留学生；
* 留服；
* 社保；
* 年龄；
* 工作单位；
* 专业认定。

输入：

```text
岗位要求
上位公告
相关政策
候选人画像
```

输出：

```text
结论
证据
不确定项
需要人工确认项
```

---

# 32. Eligibility Agent

职责：

只回答：

> 能不能报。

不负责：

> 值不值得去。

输出：

```json
{
  "status": "CONDITIONAL",
  "blockingReasons": [],
  "uncertainties": [
    "需确认境外学历认定口径"
  ],
  "evidence": []
}
```

---

# 33. Matching Agent

输入：

```text
Candidate
Job
Organization
```

分析：

* 工程经历；
* AI；
* 数据；
* 项目；
* 职称；
* 研究。

输出：

```text
适配度
优势
短板
岗位叙事
```

---

# 34. Resume Strategy Agent

不直接修改事实。

只决定：

> 哪些真实经历应该突出。

预置三类：

```text
INFORMATION_SYSTEM_RESUME
信息化 / 医院 / 高校

AI_RESEARCH_RESUME
AI / 科技服务 / 研究

DIGITAL_PLATFORM_RESUME
国企 / 数据平台 / 数字政府
```

---

# 35. Interview Agent

根据岗位生成：

```text
笔试准备
专业测试
结构化面试
技术问题
业务问题
单位理解
```

并维护历史题型库。

---

# 36. Policy Watch Agent

任务：

发现政策变化。

重点：

```text
年龄
应届定义
境外学历
留服
未落实工作单位
社保
职称
专业目录
```

新旧政策必须 Diff。

输出：

```text
CHANGE
NO_CHANGE
POTENTIAL_IMPACT
```

---

# 37. Opportunity Agent

负责：

```text
deadline
next action
material checklist
application status
```

不直接代替用户提交报名。

---

# 38. Forecast Agent

只能做：

> 有依据的概率预测。

必须输出：

```text
预测
置信区间/等级
历史依据
不确定性
```

---

# 39. 规则引擎与 LLM 边界

这是系统最重要的工程原则之一。

## Java Rule Engine

负责：

```text
年龄计算
学历比较
日期计算
毕业年份比较
是否博士硬门槛
是否专业精确匹配
工作年限
deadline
分数公式
```

## LLM

负责：

```text
模糊专业匹配
政策语义理解
岗位职责理解
岗位族分类
优势解释
简历策略
历史语义聚类
```

禁止：

```text
让 LLM 自己算年龄硬门槛
让 LLM 自己决定报名截止时间
让 LLM 无证据判断事业编
```

---

# 40. 评分模型

不要只有一个总分。

UI 必须展示多维度。

至少：

```text
Eligibility
Fit
Chance
Stability
Growth
Future
```

---

# 41. Eligibility

属于 Gate。

如果：

```text
INELIGIBLE
```

则默认：

```text
Recommended = false
```

但仍保留历史分析价值。

---

# 42. Fit Score

0–100。

建议权重：

```text
专业匹配       25
技能匹配       20
工程经历       20
研究匹配       10
职称匹配       10
岗位方向偏好   15
```

---

# 43. Stability Score

```text
用工确定性     30
单位稳定       25
财政/股东背景  20
行业稳定       15
政策连续性     10
```

---

# 44. Chance Score

不能叫“录取概率”除非有可靠模型。

建议产品名称：

> Opportunity Success Score

考虑：

```text
资格完整度
匹配度
招聘人数
专业限制宽度
考试方式
候选人的优势
竞争环境
```

---

# 45. Growth Score

```text
技术成长
晋升路径
职称体系
可迁移能力
未来市场价值
```

---

# 46. Future Score

预测后续年度类似机会出现可能性。

输入：

```text
历史重复
单位招聘频率
岗位族频率
政策趋势
组织扩张
```

---

# 47. 综合排序

建议：

```text
PriorityScore =
0.30 * Fit
+ 0.25 * Chance
+ 0.20 * Stability
+ 0.10 * Growth
+ 0.15 * Future
```

Eligibility 是前置 Gate，不直接混入。

权重必须配置化。

---

# 48. “概率”业务规则

禁止出现没有数据依据的：

```text
你有80%概率录取
```

系统应该区分：

```text
score
ranking
probability
```

只有经过历史校准后，才允许称为 probability。

V1 默认：

```text
Opportunity Success Score
```

---

# 49. 历史数据库目标字段

第一版 Excel / DB 至少包括：

```text
年份
省份
城市
区县
招聘事件
主管部门
单位
单位性质
用工性质
岗位名称
岗位类别
岗位等级
人数
岗位职责

专业原文
专业标准化

学历
学位

年龄
年龄原文

应届要求
境外学历规则
留服规则
未落实工作单位
社保限制

工作经历
职称要求
论文要求
项目要求

笔试
笔试科目
专业测试
面试形式

报名开始
报名截止

来源URL
官方URL
附件

岗位族

2025是否出现
2026是否出现
重复次数

Eligibility
Fit
Chance
Stability
Growth
Future

人工审核状态
证据完整度
备注
```

---

# 50. 历史数据重点岗位族

P0：

```text
政府信息中心
大数据发展中心
数据局技术岗
医院信息中心
高校信息化中心
科研院工程技术
科技创新服务
人工智能研究
AI应用
产业数字化
数据治理
软件平台
政务云
数字政府
政府背景数据集团
```

---

# 51. 核心单位画像

系统不能只搜索招聘。

还要建立长期 Watchlist。

至少包括：

```text
数据局体系
发改委体系
经信体系
科技体系
卫健系统信息中心
高校信息化
科研院所
数据集团
数字政府平台
```

每个单位记录：

```text
last_recruitment
recruitment_frequency
technical_job_frequency
organization_stability
watch_priority
```

---

# 52. 搜索发现机制

采用“两阶段”。

## Discovery

允许：

```text
搜索引擎
第三方招聘网站
聚合平台
```

只用于发现。

## Verification

最终关键字段：

尽量验证：

```text
官方公告
官方附件
主管部门
单位官网
```

---

# 53. Source Reliability

```text
100 官方政府
95 主管部门
95 单位官网
90 官方招聘平台
70 大型招聘聚合
50 普通转载
30 搜索摘要
```

任何资格结论显示：

```text
Evidence Confidence
```

---

# 54. Opportunity Pipeline

流程：

```text
发现
↓
自动筛选
↓
人工关注
↓
资格分析
↓
材料准备
↓
报名
↓
笔试
↓
面试
↓
体检
↓
公示
↓
Offer
```

系统必须允许用户跳过步骤。

---

# 55. 材料管理

MVP 可只存 Metadata。

例如：

```text
简历版本
成绩单
毕业证明
留服
职称
工作证明
论文
项目
身份证明
```

不得把敏感证件默认上传云端。

---

# 56. 复盘系统

每次失败记录：

```text
SCREENING_FAILED
QUALIFICATION_FAILED
WRITTEN_EXAM_FAILED
INTERVIEW_FAILED
WITHDRAWN
OTHER
```

以及：

```text
failure_notes
estimated_reason
confirmed_reason
```

后续用于校正：

`Chance Score`。

---

# 57. MVP 范围

MVP 不做完整 SaaS。

目标：

> 用真实 2025–2026 杭州数据证明系统有效。

必须完成：

### Data

* Excel 导入
* HTML 公告导入
* URL记录
* 单位
* 岗位
* 基础政策字段

### Candidate

* 一个 Candidate Profile

### Rules

* 年龄
* 学历
* 专业
* 应届
* 工作经历
* 职称

### Analysis

* Eligibility
* Fit
* Stability

### UI

* 岗位列表
* 岗位详情
* Top Ranking
* Review Queue

### Agent

只实现：

1. Recruitment Extraction Agent
2. Eligibility Explanation Agent

---

# 58. MVP 明确不做

```text
自动投递
复杂多Agent自治
浏览器登录
验证码绕过
自动报名
全国招聘
复杂预测模型
薪酬精确预测
完整简历生成器
自动面试语音
```

---

# 59. 技术架构

推荐：

```text
Frontend
React / Vue

        ↓

Spring Boot API

        ↓

Domain Services

├── Recruitment Service
├── Organization Service
├── Candidate Service
├── Eligibility Service
├── Scoring Service
├── Opportunity Service
├── Source Service
└── Agent Orchestrator

        ↓

PostgreSQL
+ pgvector

        ↓

Object Storage / Local Raw Archive
```

---

# 60. 技术选型

Backend：

```text
Java 21
Spring Boot 3
Spring Data JPA / MyBatis
PostgreSQL
Flyway
Redis（V1可选）
```

AI：

```text
Spring AI
或 LangChain4j

建议 MVP 只选一个。
优先 Spring AI。
```

Vector：

```text
pgvector
```

Extraction：

```text
Apache POI
Apache Tika / PDFBox
Jsoup
Playwright
```

Testing：

```text
JUnit 5
Testcontainers
WireMock
```

Deployment：

```text
Docker Compose
```

---

# 61. 为什么暂时不需要 Elasticsearch

MVP 数据量很小。

优先：

```text
PostgreSQL Full Text Search
+
pgvector
```

后续数据达到明显规模再引入 Elasticsearch。

避免过度设计。

---

# 62. Agent 架构

MVP：

```text
CareerAgentOrchestrator
        |
        ├─ RecruitmentExtractor
        |
        └─ EligibilityExplainer
```

V1：

```text
PolicyAgent
MatchingAgent
ResumeStrategyAgent
InterviewAgent
```

V2：

```text
ForecastAgent
CareerSimulator
SourceDiscoveryAgent
```

---

# 63. 项目目录

建议：

```text
career-os/

├── README.md
├── docker-compose.yml

├── docs/
│   ├── CAREER_OS_MASTER_SPEC.md
│   ├── DOMAIN_MODEL.md
│   ├── DATA_SOURCES.md
│   ├── SCORING_MODEL.md
│   └── ADR/

├── backend/
│   └── career-api/

├── frontend/

├── data/
│   ├── raw/
│   │   ├── 2025/
│   │   └── 2026/
│   ├── fixtures/
│   └── seeds/

├── crawler/
├── parser/
├── scripts/

└── tests/
```

如果全部 Java，可把 crawler/parser 作为 Maven modules。

---

# 64. 推荐 Maven 模块

```text
career-os-parent

career-domain
career-application
career-infrastructure
career-web
career-agent
career-ingestion
```

遵循：

```text
Domain
↑
Application
↑
Infrastructure / Web
```

不要让：

`domain` 依赖 Spring AI。

---

# 65. API 第一版

至少：

```text
POST /api/jobs/import/excel

POST /api/jobs/import/url

GET /api/jobs

GET /api/jobs/{id}

GET /api/organizations

GET /api/candidates/{id}

POST /api/eligibility/evaluate

GET /api/recommendations

POST /api/opportunities

PATCH /api/opportunities/{id}/status

GET /api/reviews
```

---

# 66. 数据质量规则

每个 Job 必须有：

```text
organization
job_name
source
year
```

没有则拒绝入正式库。

允许进入：

```text
quarantine
```

---

# 67. 数据状态

```text
RAW
PARSED
NORMALIZED
REVIEW_REQUIRED
VERIFIED
REJECTED
```

---

# 68. AI Structured Output

所有 Agent 必须使用 JSON Schema / Typed DTO。

禁止核心业务：

```text
String response = llm.chat(...)
```

然后直接入库。

LLM 输出必须：

```text
validate
normalize
evidence check
```

后再写入数据库。

---

# 69. Prompt 基本原则

System Prompt 必须包含：

```text
DO NOT infer missing eligibility facts.
DO NOT infer employment type.
Use UNKNOWN when evidence is insufficient.
Quote or reference evidence for every restrictive rule.
Separate fact from interpretation.
```

---

# 70. Observability

Agent 调用记录：

```text
model
prompt_version
latency
token_usage
response_status
validation_error
```

Crawler：

```text
source
status
HTTP code
last success
document hash
```

---

# 71. 版本管理

公告可能修改。

所以招聘信息要支持：

```text
document_hash
version
first_seen_at
last_seen_at
```

如果公告变化：

不能覆盖。

创建 Revision。

---

# 72. 安全与合规

不得：

* 绕验证码；
* 绕登录限制；
* 高频攻击网站；
* 自动代替用户提交事业单位报名；
* 抓取明显禁止自动采集的个人信息。

尊重：

* robots；
* rate limit；
* 网站服务条款。

优先抓：

公开招聘公告和公开附件。

---

# 73. MVP 验收数据集

使用真实：

```text
2026杭州市市属事业单位招聘 Excel
2026高层次和特殊专业技术岗位 Excel
2025杭州历史招聘数据
```

人工建立至少：

```text
30–50个 Golden Jobs
```

用于测试。

---

# 74. Golden Test

例如：

```text
杭州市西溪医院
信息中心工作人员
```

预期：

```text
专业匹配：PASS
学历：PASS
年龄：PASS
应届：PASS / 不限制
技术匹配：HIGH
岗位族：HOSPITAL_IT
```

---

# 75. Agent Extraction Test

真实公告输入。

必须校验：

```text
招聘人数
年龄
学历
专业
应届
社保
考试形式
```

Target：

关键硬条件 Precision 优先。

宁可：

```text
UNKNOWN
```

不要错误抽取。

---

# 76. Eligibility Test

所有硬资格使用 deterministic test。

例如：

```text
candidate birth = 1992-09
ageLimit = 38
referenceDate = YYYY-MM-DD
```

必须测试边界日期。

---

# 77. 第一阶段成功指标

不是访问量。

是：

### 数据

* ≥ 200个真实岗位；
* ≥ 30个重点单位；
* 覆盖2025、2026；
* ≥ 80%有官方证据源。

### 解析

关键资格字段：

```text
Precision ≥ 95%
```

允许 Recall 较低。

### 人工效率

每100个原始岗位：

人工最终审核时间显著下降。

### 决策

能够稳定输出：

```text
TOP20 值得关注机会
```

并解释为什么。

---

# 78. Phase 0 — 基线

Codex 第一轮只做：

```text
1. 创建项目
2. 创建 docs
3. 创建领域模型
4. 创建 PostgreSQL Schema
5. 创建 Flyway migrations
6. 创建 Golden Dataset
7. 创建测试
```

**不写爬虫。**

---

# 79. Phase 1 — Java MVP

实现：

```text
Job CRUD
Organization CRUD
Candidate Profile
Excel Import
Eligibility Rule Engine
Basic Ranking
Opportunity Tracker
```

验收：

真实 2026 Excel 可以导入。

---

# 80. Phase 2 — Extraction Agent

实现：

```text
HTML Parser
PDF Parser
LLM structured extraction
Evidence
Review Queue
```

---

# 81. Phase 3 — Source Acquisition

再开发：

```text
official static crawler
attachment downloader
scheduler
Playwright connector
```

---

# 82. Phase 4 — Intelligence

增加：

```text
Policy Agent
Semantic Matching
Historical Grouping
Forecast
```

---

# 83. Phase 5 — Career OS

增加：

```text
Resume Strategy
Interview
Calendar
Materials
Career Simulator
Review Learning
```

---

# 84. Codex 实施规则

Codex 在任何阶段必须遵守：

### Rule 1

先读：

```text
docs/CAREER_OS_MASTER_SPEC.md
```

### Rule 2

任何新需求如果与主文档冲突：

禁止自行改业务规则。

创建：

```text
docs/ADR/ADR-xxxx.md
```

记录决策。

### Rule 3

每个 Phase：

```text
test first
implementation
validation
```

### Rule 4

不得自行增加：

```text
Kafka
Kubernetes
微服务
Elasticsearch
复杂工作流引擎
```

除非出现实际需求。

### Rule 5

MVP 优先单体模块化架构。

### Rule 6

关键业务必须有测试。

### Rule 7

LLM 不得成为硬资格唯一判断来源。

### Rule 8

任何概率必须区分：

```text
事实
评分
估计
预测
```

---

# 85. Codex 第一条实施 Prompt

将本文件放入项目：

```text
docs/CAREER_OS_MASTER_SPEC.md
```

然后给 Codex：

```text
You are implementing Career OS.

Read docs/CAREER_OS_MASTER_SPEC.md completely before making any changes.

This document is the authoritative business and architecture specification.

Task:
Implement Phase 0 only.

Requirements:
1. Create a modular Java 21 + Spring Boot 3 project.
2. Use PostgreSQL and Flyway.
3. Model the initial core domains:
   - RecruitmentEvent
   - Organization
   - JobPosting
   - CandidateProfile
   - PolicyRule
   - Evidence
   - EligibilityAssessment
   - Opportunity
4. Create migrations.
5. Create repositories and domain-level types.
6. Add validation.
7. Create a seed candidate profile and Golden Job fixtures based on test data.
8. Write tests for:
   - age eligibility
   - education eligibility
   - exact major matching
   - graduate restriction
   - work experience restriction
9. Do NOT implement crawling.
10. Do NOT implement LLM integration.
11. Do NOT implement frontend.
12. Do NOT add infrastructure not required by the specification.
13. Keep uncertain information as UNKNOWN rather than inferring it.

Before implementation:
- produce a short implementation plan;
- identify ambiguities;
- resolve only engineering ambiguities;
- do not alter business requirements.

After implementation:
- run all tests;
- provide architecture summary;
- list created files;
- list unresolved domain questions.
```

---

# 86. 最重要的产品原则

Career OS 的价值不在：

> 抓更多岗位。

而在：

> **让低质量、零散、难理解的招聘和政策信息，转变成可靠、可解释、可执行的职业决策。**

因此优先级始终是：

```text
Correctness
>
Evidence
>
Eligibility
>
Decision Quality
>
Automation
>
Number of Crawled Jobs
```

---

# 87. 项目最终定位

对真实用户：

> 个人职业机会与稳定职业决策系统。

对技术项目：

> Java + LLM + Agent + RAG + Rules + Data Intelligence 的企业级 AI 应用。

对第一阶段数据：

> 2025–2026 浙江/杭州公共数字技术岗位历史库。

最终形成三项长期资产：

```text
历史岗位数据
+
政策知识库
+
个性化职业决策模型
```

而不是一次性的招聘爬虫。

---

# 88. 第一阶段 Done Definition

只有同时满足以下条件，Phase 1 才算成功：

* [ ] 能导入真实杭州招聘 Excel
* [ ] 能显示结构化岗位
* [ ] 能识别单位和招聘事件
* [ ] 能判断基本硬资格
* [ ] 每个资格结论能追溯证据
* [ ] 能标记 UNKNOWN
* [ ] 能按照个人画像筛选
* [ ] 能输出高匹配岗位
* [ ] 能进入 Opportunity 管理
* [ ] 有 Golden Test 防止模型胡判
* [ ] 没有依赖 LLM 完成确定性规则
* [ ] 没有未经证据确认“事业编”“社保限制”等事实

达到这一阶段以后，才开始做自动爬取和真正的 Agent。

