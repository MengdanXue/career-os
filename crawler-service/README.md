# Career OS Crawler Service

当前已实现四条真实数据链：

1. S01–S06 Excel：解析 320 条事业单位/高校岗位。
2. S07 DOCX：解析 8 条杭州电子科技大学劳务派遣岗位。
3. S08 PDF：解析 20 页、15 章、79 条应聘规则问答。
4. S09–S10 国企官网：扫描招聘入口、区分真实空状态与失败状态、发现公告附件，并把最新 Excel 继续解析为岗位 JSON。

所有岗位记录包含确定性 `job_id`、文件 SHA-256、原始位置和原文证据，并按 v1.1 Job Schema 校验。招聘源扫描使用独立的 Source Scan Schema。

## 构建与测试

```powershell
$env:JAVA_HOME='D:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn clean test
mvn package
```

## 运行 S09/S10 国企招聘链路

```powershell
java -cp target/crawler-service-0.1.0-SNAPSHOT-all.jar `
  com.careeros.crawler.SoeRecruitmentApp
```

该命令会：

1. 从杭州资本招聘规范第一页开始扫描，不使用可能过期的历史末页 URL。
2. 读取公告详情并发现 Excel/Word 附件。
3. 扫描杭州金投 28 个企业岗位区块并识别明确的“暂无岗位”。
4. 下载最新招聘计划 Excel，解析、分类并按 Job Schema 校验。

默认输出到 `output/career-os-samples/actual/soe`。2026-08-14 的实测结果为：S09 有 6 条公告、12 个附件；最新杭氧公告包含 101 个岗位、计划招聘 198 人；附件表解析出 101 条岗位记录；S10 的 28 个企业区块均明确为空。

## 运行既有样本

S07 DOCX：

```powershell
java -jar target/crawler-service-0.1.0-SNAPSHOT-all.jar
```

S01–S06 Excel：

```powershell
java -cp target/crawler-service-0.1.0-SNAPSHOT-all.jar `
  com.careeros.crawler.SpreadsheetBatchApp
```

S08 PDF：

```powershell
java -cp target/crawler-service-0.1.0-SNAPSHOT-all.jar `
  com.careeros.crawler.PdfGuideApp
```

接入新表格前可先运行只读探测工具：

```powershell
java -cp target/crawler-service-0.1.0-SNAPSHOT-all.jar `
  com.careeros.crawler.tool.WorkbookInspector '<workbook.xlsx>'
```
