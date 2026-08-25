[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$SourceCode = 'HZ_XIHU_GOV',
    [switch]$RunSource
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $projectRoot 'compose.yaml'
$base = $BaseUrl.TrimEnd('/')

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "LIFECYCLE ACCEPTANCE FAILED: $Message" }
}

function Get-Sources {
    return @(Invoke-RestMethod -Uri "$base/api/acquisition/sources" -TimeoutSec 30)
}

function Wait-Run([object]$Run) {
    for ($attempt = 0; $Run.status -eq 'RUNNING' -and $attempt -lt 120; $attempt++) {
        Start-Sleep -Milliseconds 500
        $Run = Invoke-RestMethod -Uri "$base/api/acquisition/runs/$($Run.id)" -TimeoutSec 30
    }
    Assert-Condition ($Run.status -ne 'RUNNING') "采集运行 $($Run.id) 超时"
    return $Run
}

function Start-SourceRun([object]$Source) {
    $run = Invoke-RestMethod -Method Post -Uri "$base/api/acquisition/sources/$($Source.id)/runs" -TimeoutSec 180
    return Wait-Run $run
}

function Invoke-Scalar([string]$Sql) {
    $value = (docker compose -f $composeFile exec -T postgres psql -U career_os -d career_os -tA -c $Sql | Out-String).Trim()
    Assert-Condition ($LASTEXITCODE -eq 0) '无法查询 PostgreSQL 验收数据'
    return [long]$value
}

Push-Location $projectRoot
try {
    $health = Invoke-RestMethod -Uri "$base/actuator/health" -TimeoutSec 10
    Assert-Condition ($health.status -eq 'UP') 'Career OS 健康检查未通过'

    $sources = Get-Sources
    $source = $sources | Where-Object code -eq $SourceCode | Select-Object -First 1
    Assert-Condition ($null -ne $source) "未找到来源 $SourceCode"
    Assert-Condition ($null -ne $source.id) "来源 $SourceCode 尚未绑定采集器"

    $firstRun = $null
    $secondRun = $null
    if ($RunSource) {
        $firstRun = Start-SourceRun $source
        $secondRun = Start-SourceRun $source
        Assert-Condition ($firstRun.status -in @('SUCCEEDED', 'PARTIALLY_SUCCEEDED')) "第一轮采集状态不可接受：$($firstRun.status)"
        Assert-Condition ($secondRun.status -in @('SUCCEEDED', 'PARTIALLY_SUCCEEDED')) "第二轮采集状态不可接受：$($secondRun.status)"
        Assert-Condition ($secondRun.addedCount -eq 0 -and $secondRun.updatedCount -eq 0) `
            "第二轮采集不幂等：新增 $($secondRun.addedCount)，更新 $($secondRun.updatedCount)"
    }

    $sourceId = $source.id
    $documentCount = Invoke-Scalar @"
select count(distinct lifecycle.source_url)
from recruitment_lifecycle_document lifecycle
join acquired_document document
  on document.source_id = '$sourceId'::uuid
 and document.canonical_uri = lifecycle.source_url;
"@
    $matched = Invoke-Scalar @"
select count(distinct lifecycle.source_url)
from recruitment_lifecycle_document lifecycle
join acquired_document document
  on document.source_id = '$sourceId'::uuid
 and document.canonical_uri = lifecycle.source_url
where lifecycle.match_status = 'MATCHED';
"@
    $unmatched = Invoke-Scalar @"
select count(distinct lifecycle.source_url)
from recruitment_lifecycle_document lifecycle
join acquired_document document
  on document.source_id = '$sourceId'::uuid
 and document.canonical_uri = lifecycle.source_url
where lifecycle.match_status = 'UNMATCHED';
"@
    $ambiguous = Invoke-Scalar @"
select count(distinct lifecycle.source_url)
from recruitment_lifecycle_document lifecycle
join acquired_document document
  on document.source_id = '$sourceId'::uuid
 and document.canonical_uri = lifecycle.source_url
where lifecycle.match_status = 'AMBIGUOUS';
"@
    $duplicates = Invoke-Scalar @"
select count(*) from (
  select source_url, stage
  from recruitment_lifecycle_document
  group by source_url, stage
  having count(*) > 1
) duplicate_rows;
"@
    $invalidLinks = Invoke-Scalar @"
select count(*)
from recruitment_lifecycle_document
where (match_status = 'MATCHED' and matched_event_id is null)
   or (match_status <> 'MATCHED' and matched_event_id is not null);
"@
    $falseEvents = Invoke-Scalar @"
select count(distinct event.id)
from recruitment_event event
join recruitment_lifecycle_document lifecycle on lifecycle.source_url = event.source_url;
"@

    Assert-Condition ($duplicates -eq 0) "存在 $duplicates 组重复的来源 URL + 生命周期阶段"
    Assert-Condition ($invalidLinks -eq 0) "存在 $invalidLinks 条状态与招聘事件引用矛盾的数据"
    Assert-Condition ($falseEvents -eq 0) "有 $falseEvents 条后续公告被误建为独立招聘事件"
    Assert-Condition ($matched + $unmatched + $ambiguous -eq $documentCount) '生命周期状态汇总与公告数不一致'

    $sources = Get-Sources
    $source = $sources | Where-Object code -eq $SourceCode | Select-Object -First 1
    Assert-Condition ($source.lifecycleDocumentCount -eq $documentCount) '来源 API 的后续公告总数与数据库不一致'
    Assert-Condition ($source.matchedLifecycleCount -eq $matched) '来源 API 的已关联数与数据库不一致'
    Assert-Condition ($source.unmatchedLifecycleCount -eq $unmatched) '来源 API 的待关联数与数据库不一致'
    Assert-Condition ($source.ambiguousLifecycleCount -eq $ambiguous) '来源 API 的歧义数与数据库不一致'

    [pscustomobject]@{
        sourceCode = $SourceCode
        firstRun = if ($null -eq $firstRun) { $null } else { [pscustomobject]@{ status = $firstRun.status; added = $firstRun.addedCount; updated = $firstRun.updatedCount; unchanged = $firstRun.unchangedCount; failed = $firstRun.failedCount } }
        secondRun = if ($null -eq $secondRun) { $null } else { [pscustomobject]@{ status = $secondRun.status; added = $secondRun.addedCount; updated = $secondRun.updatedCount; unchanged = $secondRun.unchangedCount; failed = $secondRun.failedCount } }
        lifecycle = [pscustomobject]@{ documents = $documentCount; matched = $matched; unmatched = $unmatched; ambiguous = $ambiguous }
        invariants = [pscustomobject]@{ duplicateStageRows = $duplicates; invalidLinks = $invalidLinks; falseRecruitmentEvents = $falseEvents }
        status = 'PASS'
    } | ConvertTo-Json -Depth 6
} finally {
    Pop-Location
}
