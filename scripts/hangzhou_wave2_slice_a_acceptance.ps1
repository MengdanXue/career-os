[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [switch]$RunSources
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $projectRoot 'compose.yaml'

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-Sources {
    return @(Invoke-RestMethod -Uri "$BaseUrl/api/acquisition/sources" -TimeoutSec 30)
}

function Run-Source([object]$Source) {
    Assert-Condition ($null -ne $Source.id) "来源 $($Source.code) 尚未绑定采集器"
    return Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/acquisition/sources/$($Source.id)/runs" -TimeoutSec 180
}

Push-Location $projectRoot
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
    Assert-Condition ($health.status -eq 'UP') 'Career OS 健康检查未通过'

    $sources = Get-Sources
    $p0Districts = @($sources | Where-Object { $_.scopeLevel -eq 'DISTRICT' -and $_.priorityTier -eq 'P0' })
    $p2Districts = @($sources | Where-Object { $_.scopeLevel -eq 'DISTRICT' -and $_.priorityTier -eq 'P2' })
    Assert-Condition ($p0Districts.Count -eq 10) "杭州 P0 区县目标应为 10，实际为 $($p0Districts.Count)"
    Assert-Condition ($p2Districts.Count -eq 3) "杭州 P2 县市目标应为 3，实际为 $($p2Districts.Count)"
    Assert-Condition (-not ($sources | Where-Object { $_.connectionStatus -eq 'CONNECTED' -and $_.accessStatus -eq 'NOT_CONFIGURED' })) `
        '未配置采集器的来源不能标记为 CONNECTED'
    Assert-Condition (-not ($sources | Where-Object { $null -eq $_.accessStatus -or $null -eq $_.documentIssueCount })) `
        '来源 API 必须分别报告官网访问状态和附件问题数'

    if ($RunSources) {
        $hangzhou = $sources | Where-Object code -eq 'HZ_HRSS_INSTITUTION' | Select-Object -First 1
        Assert-Condition ($null -ne $hangzhou) '未找到杭州市人社局来源'
        $firstRun = Run-Source $hangzhou
        $secondRun = Run-Source $hangzhou
        Assert-Condition ($secondRun.addedCount -eq 0 -and $secondRun.updatedCount -eq 0) `
            "第二次增量运行不幂等：新增 $($secondRun.addedCount)，更新 $($secondRun.updatedCount)"
        Assert-Condition ($secondRun.status -in @('SUCCEEDED', 'PARTIALLY_SUCCEEDED')) `
            "第二次增量运行状态不可接受：$($secondRun.status)"
    }

    $pseudoJobSql = @"
select count(*)
from job_posting job
join organization organization_row on organization_row.id = job.organization_id
where job.active = true
  and organization_row.name = '招聘单位'
  and job.title = '招聘岗位'
  and job.external_job_code = '序号';
"@
    $pseudoJobCount = (docker compose -f $composeFile exec -T postgres psql -U career_os -d career_os -tA -c $pseudoJobSql | Out-String).Trim()
    Assert-Condition ($LASTEXITCODE -eq 0) '无法查询 PostgreSQL 验收数据'
    Assert-Condition ($pseudoJobCount -eq '0') "仍有 $pseudoJobCount 条重复表头伪岗位处于启用状态"

    $sources = Get-Sources
    $summary = [pscustomobject]@{
        totalTargets = $sources.Count
        p0Districts = $p0Districts.Count
        p2Districts = $p2Districts.Count
        connected = @($sources | Where-Object connectionStatus -eq 'CONNECTED').Count
        partial = @($sources | Where-Object connectionStatus -eq 'PARTIAL').Count
        failed = @($sources | Where-Object connectionStatus -eq 'FAILED').Count
        notConnected = @($sources | Where-Object connectionStatus -eq 'NOT_CONNECTED').Count
        accessible = @($sources | Where-Object accessStatus -eq 'ACCESSIBLE').Count
        documentIssues = ($sources | Measure-Object -Property documentIssueCount -Sum).Sum
        activePseudoJobs = [int]$pseudoJobCount
    }
    $summary | ConvertTo-Json -Depth 3
} finally {
    Pop-Location
}
