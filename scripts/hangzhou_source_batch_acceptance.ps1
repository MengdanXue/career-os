[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string[]]$Codes = @(),
    [int]$FromYear = 2024,
    [int]$ToYear = 2026,
    [string]$OutputPath = '.run/hangzhou-source-acceptance.json',
    [int]$RequestTimeoutSeconds = 1800,
    [int]$RunTimeoutMinutes = 30,
    [switch]$Resume,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "SOURCE ACCEPTANCE FAILED: $Message" }
}

function Test-RunStatus([string]$Status) {
    return $Status -in @('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
}

function Test-MeaningfulRun([object]$Run) {
    if ($Run.status -eq 'SUCCEEDED') {
        return $true
    }
    if ($Run.status -ne 'PARTIALLY_SUCCEEDED') {
        return $false
    }
    $successfulItems = [int]$Run.fetchedCount + [int]$Run.unchangedCount + `
        [int]$Run.addedCount + [int]$Run.updatedCount
    return $successfulItems -gt 0
}

function Resolve-OutputPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Path))
}

function Get-Sources([string]$Base) {
    return @(Invoke-RestMethod -Uri "$Base/api/acquisition/sources" -TimeoutSec 60)
}

function Wait-Run([string]$Base, [object]$Run) {
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes($RunTimeoutMinutes)
    while ($Run.status -eq 'RUNNING' -and [DateTimeOffset]::UtcNow -lt $deadline) {
        Start-Sleep -Seconds 1
        $Run = Invoke-RestMethod -Uri "$Base/api/acquisition/runs/$($Run.id)" -TimeoutSec 60
    }
    Assert-Condition ($Run.status -ne 'RUNNING') "运行 $($Run.id) 超过 $RunTimeoutMinutes 分钟"
    return $Run
}

function Start-Incremental([string]$Base, [object]$Source) {
    $run = Invoke-RestMethod -Method Post `
        -Uri "$Base/api/acquisition/sources/$($Source.id)/runs" `
        -TimeoutSec $RequestTimeoutSeconds
    return Wait-Run -Base $Base -Run $run
}

function Start-Historical([string]$Base, [object]$Source) {
    $response = Invoke-RestMethod -Method Post `
        -Uri "$Base/api/acquisition/sources/$($Source.id)/historical-runs?fromYear=$FromYear&toYear=$ToYear" `
        -TimeoutSec $RequestTimeoutSeconds
    $run = Wait-Run -Base $Base -Run $response.run
    return [pscustomobject]@{ run = $run; coverage = @($response.coverage) }
}

function Get-Coverage([string]$Base, [object]$Source) {
    $coverage = [System.Collections.Generic.List[object]]::new()
    foreach ($year in $FromYear..$ToYear) {
        $rows = @(Invoke-RestMethod `
            -Uri "$Base/api/acquisition/coverage?sourceId=$($Source.id)&year=$year" `
            -TimeoutSec 60)
        Assert-Condition ($rows.Count -eq 1) "来源 $($Source.code) 的 $year 年覆盖记录应恰好一条"
        $coverage.Add($rows[0])
    }
    return @($coverage)
}

function Convert-Run([object]$Run) {
    return [pscustomobject][ordered]@{
        id = $Run.id
        status = $Run.status
        discovered = [int]$Run.discoveredCount
        fetched = [int]$Run.fetchedCount
        unchanged = [int]$Run.unchangedCount
        added = [int]$Run.addedCount
        updated = [int]$Run.updatedCount
        deactivated = [int]$Run.deactivatedCount
        failed = [int]$Run.failedCount
        errorCode = $Run.errorCode
        errorMessage = $Run.errorMessage
    }
}

function Convert-Coverage([object[]]$Coverage) {
    return @($Coverage | Sort-Object year | ForEach-Object {
        [pscustomobject][ordered]@{
            year = [int]$_.year
            status = $_.status
            discovered = [int]$_.discoveredCount
            fetched = [int]$_.fetchedCount
            parsed = [int]$_.parsedCount
            targetJobs = [int]$_.targetJobCount
            listingPages = [int]$_.listingPageCount
            filtered = [int]$_.filteredCount
            failed = [int]$_.failedCount
            earliestPublishedOn = $_.earliestPublishedOn
            latestPublishedOn = $_.latestPublishedOn
            stopReason = $_.stopReason
            supportsAbsenceConclusion = [bool]$_.supportsAbsenceConclusion
        }
    })
}

function Save-State([object]$State, [string]$Path) {
    $resolved = Resolve-OutputPath -Path $Path
    $directory = Split-Path -Parent $resolved
    if (-not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    $temporary = "$resolved.tmp"
    $State | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination $resolved -Force
}

function New-State([string]$Base, [string[]]$SelectedCodes) {
    return [pscustomobject][ordered]@{
        schemaVersion = 2
        baseUrl = $Base
        fromYear = $FromYear
        toYear = $ToYear
        selectedCodes = @($SelectedCodes)
        startedAt = [DateTimeOffset]::UtcNow.ToString('o')
        completedAt = $null
        results = @()
        summary = $null
    }
}

function New-SourceResult([object]$Source) {
    return [pscustomobject][ordered]@{
        code = $Source.code
        sourceId = $Source.id
        status = 'IN_PROGRESS'
        stage = 'NOT_STARTED'
        startedAt = [DateTimeOffset]::UtcNow.ToString('o')
        completedAt = $null
        firstIncremental = $null
        historical = $null
        secondIncremental = $null
        coverage = @()
        error = $null
    }
}

function Assert-SourceResultState([object]$Result, [object]$Source) {
    Assert-Condition ($Result.code -eq $Source.code -and "$($Result.sourceId)" -eq "$($Source.id)") `
        "来源 $($Source.code) 的 Resume 状态与当前采集器不匹配"
    $allowedStages = @('NOT_STARTED','FIRST_INCREMENTAL_COMPLETED','HISTORICAL_COMPLETED','COMPLETED')
    Assert-Condition ($Result.stage -in $allowedStages) `
        "来源 $($Source.code) 的 Resume 阶段无效：$($Result.stage)"
    if ($Result.stage -in @('FIRST_INCREMENTAL_COMPLETED','HISTORICAL_COMPLETED','COMPLETED')) {
        Assert-Condition ($null -ne $Result.firstIncremental) `
            "来源 $($Source.code) 的首轮增量检查点缺少运行数据"
    }
    if ($Result.stage -in @('HISTORICAL_COMPLETED','COMPLETED')) {
        Assert-Condition ($null -ne $Result.historical -and @($Result.coverage).Count -eq ($ToYear - $FromYear + 1)) `
            "来源 $($Source.code) 的历史回填检查点不完整"
    }
    if ($Result.stage -eq 'COMPLETED') {
        Assert-Condition ($null -ne $Result.secondIncremental -and $Result.status -eq 'PASS') `
            "来源 $($Source.code) 的完成检查点不完整"
    }
}

function Set-SourceResult([System.Collections.Generic.List[object]]$Results, [object]$Result) {
    for ($index = 0; $index -lt $Results.Count; $index++) {
        if ($Results[$index].code -eq $Result.code) {
            $Results[$index] = $Result
            return
        }
    }
    $Results.Add($Result)
}

function Save-SourceCheckpoint([object]$State, [System.Collections.Generic.List[object]]$Results,
        [object]$Result, [string]$Path) {
    Set-SourceResult -Results $Results -Result $Result
    $State.results = @($Results)
    $State.summary = $null
    $State.completedAt = $null
    Save-State -State $State -Path $Path
}

Assert-Condition ($FromYear -ge 2000 -and $ToYear -le 2100 -and $FromYear -le $ToYear) `
    '年度范围必须位于 2000—2100 且起始年度不能晚于结束年度'
Assert-Condition ($RequestTimeoutSeconds -ge 30 -and $RunTimeoutMinutes -ge 1) '超时配置无效'
$base = $BaseUrl.TrimEnd('/')
$parsedBase = $null
Assert-Condition ([uri]::TryCreate($base, [System.UriKind]::Absolute, [ref]$parsedBase)) 'BaseUrl 必须是绝对 URL'
Assert-Condition ($parsedBase.Scheme -in @('http','https') -and $parsedBase.Host) 'BaseUrl 只允许 HTTP 或 HTTPS'
$Codes = @($Codes | ForEach-Object { $_.Trim() } | Where-Object { $_ })
Assert-Condition (@($Codes | Where-Object { $_ -notmatch '^[A-Z][A-Z0-9_]{1,99}$' }).Count -eq 0) `
    '来源代码只允许大写字母、数字和下划线'
Assert-Condition (($Codes | Select-Object -Unique).Count -eq $Codes.Count) '来源代码不能重复'

if ($ValidateOnly) {
    Write-Host "批量来源验收脚本参数有效：$FromYear—$ToYear；来源数=$($Codes.Count)。"
    exit 0
}

Push-Location $projectRoot
try {
    $health = Invoke-RestMethod -Uri "$base/actuator/health" -TimeoutSec 15
    Assert-Condition ($health.status -eq 'UP') 'Career OS 健康检查未通过'
    $sources = Get-Sources -Base $base
    $selected = if ($Codes.Count -eq 0) {
        @($sources | Where-Object { $null -ne $_.id -and $_.enabled } | Sort-Object code)
    } else {
        @($Codes | ForEach-Object {
            $code = $_
            $match = @($sources | Where-Object code -eq $code)
            Assert-Condition ($match.Count -eq 1) "未找到唯一来源 $code"
            $match[0]
        })
    }
    Assert-Condition ($selected.Count -gt 0) '没有可验收的来源'
    $selected | ForEach-Object {
        Assert-Condition ($null -ne $_.id) "来源 $($_.code) 尚未绑定采集器"
        Assert-Condition ([bool]$_.enabled) "来源 $($_.code) 未启用"
    }

    $resolvedOutput = Resolve-OutputPath -Path $OutputPath
    $selectedCodes = @($selected.code)
    $state = if ($Resume -and (Test-Path -LiteralPath $resolvedOutput -PathType Leaf)) {
        Get-Content -LiteralPath $resolvedOutput -Raw -Encoding UTF8 | ConvertFrom-Json
    } else {
        New-State -Base $base -SelectedCodes $selectedCodes
    }
    Assert-Condition ($state.schemaVersion -eq 2 -and $state.baseUrl -eq $base `
        -and $state.fromYear -eq $FromYear -and $state.toYear -eq $ToYear) `
        '已有验收文件与当前 URL 或年度范围不兼容'
    Assert-Condition ((@($state.selectedCodes) -join "`u{001f}") -eq ($selectedCodes -join "`u{001f}")) `
        '已有验收文件的来源选择或顺序与当前 Codes 不一致'
    $results = [System.Collections.Generic.List[object]]::new()
    @($state.results) | ForEach-Object { $results.Add($_) }
    Assert-Condition ((@($results.code | Select-Object -Unique).Count) -eq $results.Count) `
        'Resume 状态中存在重复来源结果'

    foreach ($source in $selected) {
        $previous = @($results | Where-Object code -eq $source.code)
        Assert-Condition ($previous.Count -le 1) "来源 $($source.code) 存在多份 Resume 状态"
        if ($previous.Count -eq 1) {
            Assert-SourceResultState -Result $previous[0] -Source $source
        }
        if ($Resume -and $previous.Count -eq 1 -and $previous[0].stage -eq 'COMPLETED' `
                -and $previous[0].status -eq 'PASS') {
            Write-Host "跳过已通过来源：$($source.code)"
            continue
        }
        Write-Host "验收来源：$($source.code)（增量 → $FromYear—$ToYear 历史 → 增量）"
        $result = if ($Resume -and $previous.Count -eq 1) {
            $previous[0]
        } else {
            New-SourceResult -Source $source
        }
        $result.status = 'IN_PROGRESS'
        $result.completedAt = $null
        $result.error = $null
        Save-SourceCheckpoint -State $state -Results $results -Result $result -Path $resolvedOutput
        try {
            if ($result.stage -eq 'NOT_STARTED') {
                $first = Start-Incremental -Base $base -Source $source
                Assert-Condition (Test-RunStatus $first.status) `
                    "来源 $($source.code) 第一轮增量状态为 $($first.status)"
                Assert-Condition (Test-MeaningfulRun $first) `
                    "来源 $($source.code) 第一轮增量虽为部分成功，但没有任何成功抓取"
                $result.firstIncremental = Convert-Run $first
                $result.stage = 'FIRST_INCREMENTAL_COMPLETED'
                Save-SourceCheckpoint -State $state -Results $results -Result $result -Path $resolvedOutput
            }
            if ($result.stage -eq 'FIRST_INCREMENTAL_COMPLETED') {
                $historical = Start-Historical -Base $base -Source $source
                Assert-Condition (Test-RunStatus $historical.run.status) `
                    "来源 $($source.code) 历史回填状态为 $($historical.run.status)"
                Assert-Condition (Test-MeaningfulRun $historical.run) `
                    "来源 $($source.code) 历史回填虽为部分成功，但没有任何成功抓取"
                $coverage = Get-Coverage -Base $base -Source $source
                $allowedCoverage = @('PARTIAL','COMPLETE','NO_TARGET_RECORDS')
                $invalidCoverage = @($coverage | Where-Object { $_.status -notin $allowedCoverage })
                Assert-Condition ($invalidCoverage.Count -eq 0) `
                    "来源 $($source.code) 存在未完成回填年度：$(@($invalidCoverage.year) -join ',')"
                $result.historical = Convert-Run $historical.run
                $result.coverage = Convert-Coverage $coverage
                $result.stage = 'HISTORICAL_COMPLETED'
                Save-SourceCheckpoint -State $state -Results $results -Result $result -Path $resolvedOutput
            }
            if ($result.stage -eq 'HISTORICAL_COMPLETED') {
                $second = Start-Incremental -Base $base -Source $source
                Assert-Condition (Test-RunStatus $second.status) `
                    "来源 $($source.code) 第二轮增量状态为 $($second.status)"
                Assert-Condition (Test-MeaningfulRun $second) `
                    "来源 $($source.code) 第二轮增量虽为部分成功，但没有任何成功抓取"
                Assert-Condition ($second.addedCount -eq 0 -and $second.updatedCount -eq 0 `
                    -and $second.deactivatedCount -eq 0) `
                    "来源 $($source.code) 第二轮不幂等：新增=$($second.addedCount)，更新=$($second.updatedCount)，下线=$($second.deactivatedCount)"
                $result.secondIncremental = Convert-Run $second
                $result.stage = 'COMPLETED'
            }
            $result.status = 'PASS'
            $result.completedAt = [DateTimeOffset]::UtcNow.ToString('o')
            $result.error = $null
        } catch {
            $result.status = 'FAIL'
            $result.completedAt = [DateTimeOffset]::UtcNow.ToString('o')
            $result.error = $_.Exception.Message
            Write-Warning "$($source.code)：$($_.Exception.Message)"
        }
        Save-SourceCheckpoint -State $state -Results $results -Result $result -Path $resolvedOutput
    }

    $latest = @($selected | ForEach-Object {
        $code = $_.code
        $results | Where-Object code -eq $code | Select-Object -Last 1
    })
    $refreshed = Get-Sources -Base $base
    $selectedState = @($refreshed | Where-Object { $_.code -in $selectedCodes })
    Assert-Condition ($selectedState.Count -eq $selected.Count) `
        '刷新后的来源状态数量与验收选择不一致'
    $state.completedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $state.results = @($results)
    $state.summary = [pscustomobject][ordered]@{
        selected = $selected.Count
        passed = @($latest | Where-Object status -eq 'PASS').Count
        failed = @($latest | Where-Object status -eq 'FAIL').Count
        connected = @($selectedState | Where-Object connectionStatus -eq 'CONNECTED').Count
        partial = @($selectedState | Where-Object connectionStatus -eq 'PARTIAL').Count
        connectionFailed = @($selectedState | Where-Object connectionStatus -eq 'FAILED').Count
        notConnected = @($selectedState | Where-Object connectionStatus -eq 'NOT_CONNECTED').Count
        accessible = @($selectedState | Where-Object accessStatus -eq 'ACCESSIBLE').Count
        accessFailed = @($selectedState | Where-Object accessStatus -eq 'ACCESS_FAILED').Count
        accessNotConfigured = @($selectedState | Where-Object accessStatus -eq 'NOT_CONFIGURED').Count
        accessUnknown = @($selectedState | Where-Object accessStatus -eq 'UNKNOWN').Count
    }
    $connectionClassified = $state.summary.connected + $state.summary.partial + `
        $state.summary.connectionFailed + $state.summary.notConnected
    $accessClassified = $state.summary.accessible + $state.summary.accessFailed + `
        $state.summary.accessNotConfigured + $state.summary.accessUnknown
    $state.summary | Add-Member -NotePropertyName connectionClassified -NotePropertyValue $connectionClassified
    $state.summary | Add-Member -NotePropertyName accessClassified -NotePropertyValue $accessClassified
    Assert-Condition ($connectionClassified -eq $selected.Count -and $accessClassified -eq $selected.Count) `
        '来源连接或可访问状态存在未分类值'
    Save-State -State $state -Path $resolvedOutput
    $state.summary | ConvertTo-Json -Depth 4
    Assert-Condition ($state.summary.failed -eq 0 -and $state.summary.connectionFailed -eq 0 `
        -and $state.summary.accessFailed -eq 0 `
        -and $state.summary.notConnected -eq 0) "验收存在失败，详见 $resolvedOutput"
} finally {
    Pop-Location
}
