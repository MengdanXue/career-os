[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script = Join-Path $PSScriptRoot 'hangzhou_source_batch_acceptance.ps1'
$testOutput = Join-Path $projectRoot 'target\hangzhou-source-acceptance-contract-test.json'
$behaviorRoot = Join-Path $projectRoot 'target\hangzhou-source-acceptance-behavior'

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "ACCEPTANCE SCRIPT CONTRACT FAILED: $Message" }
}

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
}

function Start-AcquisitionFixture([int]$Port, [string]$Mode) {
    $job = Start-Job -ScriptBlock {
        param($FixturePort, $FixtureMode)
        $listener = [System.Net.HttpListener]::new()
        $listener.Prefixes.Add("http://127.0.0.1:$FixturePort/")
        $listener.Start()
        $incrementalCalls = 0
        $historicalCalls = 0
        try {
            $running = $true
            while ($running) {
                $context = $listener.GetContext()
                $path = $context.Request.Url.AbsolutePath
                $payload = $null
                switch -Regex ($path) {
                    '^/actuator/health$' { $payload = @{ status = 'UP' }; break }
                    '^/api/acquisition/sources$' {
                        $connection = if ($FixtureMode -eq 'connection-failed') { 'FAILED' } else { 'CONNECTED' }
                        $payload = ,@{
                            id = '11111111-1111-1111-1111-111111111111'
                            code = 'TEST_SOURCE'
                            enabled = $true
                            connectionStatus = $connection
                            accessStatus = 'ACCESSIBLE'
                        }
                        break
                    }
                    '^/api/acquisition/sources/.+/historical-runs$' {
                        $historicalCalls++
                        $status = if ($FixtureMode -eq 'resume' -and $historicalCalls -eq 1) { 'FAILED' } else { 'SUCCEEDED' }
                        $payload = @{
                            run = @{
                                id = "historical-$historicalCalls"; status = $status
                                discoveredCount = 1; fetchedCount = if ($status -eq 'SUCCEEDED') { 1 } else { 0 }
                                unchangedCount = 0; addedCount = 0; updatedCount = 0
                                deactivatedCount = 0; failedCount = if ($status -eq 'FAILED') { 1 } else { 0 }
                                errorCode = $null; errorMessage = $null
                            }
                            coverage = @()
                        }
                        break
                    }
                    '^/api/acquisition/sources/.+/runs$' {
                        $incrementalCalls++
                        $partialWithoutSuccess = $FixtureMode -eq 'partial-zero' -and $incrementalCalls -eq 1
                        $payload = @{
                            id = "incremental-$incrementalCalls"
                            status = if ($partialWithoutSuccess) { 'PARTIALLY_SUCCEEDED' } else { 'SUCCEEDED' }
                            discoveredCount = if ($partialWithoutSuccess) { 2 } else { 1 }
                            fetchedCount = if ($partialWithoutSuccess) { 0 } else { 1 }
                            unchangedCount = if ($incrementalCalls -gt 1) { 1 } else { 0 }
                            addedCount = if ($incrementalCalls -eq 1 -and -not $partialWithoutSuccess) { 1 } else { 0 }
                            updatedCount = 0; deactivatedCount = 0
                            failedCount = if ($partialWithoutSuccess) { 2 } else { 0 }
                            errorCode = $null; errorMessage = $null
                        }
                        break
                    }
                    '^/api/acquisition/coverage$' {
                        $yearText = $context.Request.QueryString['year']
                        $payload = ,@{
                            year = [int]$yearText; status = 'COMPLETE'
                            discoveredCount = 1; fetchedCount = 1; parsedCount = 1; targetJobCount = 1
                            listingPageCount = 1; filteredCount = 0; failedCount = 0
                            earliestPublishedOn = "$yearText-01-01"; latestPublishedOn = "$yearText-12-31"
                            stopReason = 'FIXTURE'; supportsAbsenceConclusion = $true
                        }
                        break
                    }
                    '^/stats$' {
                        $payload = @{ incrementalCalls = $incrementalCalls; historicalCalls = $historicalCalls }
                        break
                    }
                    '^/shutdown$' { $payload = @{ stopped = $true }; $running = $false; break }
                    default { $context.Response.StatusCode = 404; $payload = @{ error = "unknown path $path" } }
                }
                $json = $payload | ConvertTo-Json -Depth 8 -Compress
                $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
                $context.Response.ContentType = 'application/json; charset=utf-8'
                $context.Response.ContentLength64 = $bytes.Length
                $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
                $context.Response.OutputStream.Close()
            }
        } finally {
            $listener.Stop()
            $listener.Close()
        }
    } -ArgumentList $Port, $Mode

    $baseUrl = "http://127.0.0.1:$Port"
    $ready = $false
    for ($attempt = 0; $attempt -lt 50 -and -not $ready; $attempt++) {
        try {
            Invoke-RestMethod -Uri "$baseUrl/stats" -TimeoutSec 1 | Out-Null
            $ready = $true
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    Assert-Condition $ready "假采集服务未能在端口 $Port 启动"
    return [pscustomobject]@{ Job = $job; BaseUrl = $baseUrl }
}

function Stop-AcquisitionFixture([object]$Fixture) {
    if ($null -eq $Fixture) { return }
    try { Invoke-RestMethod -Uri "$($Fixture.BaseUrl)/shutdown" -TimeoutSec 2 | Out-Null } catch {}
    Wait-Job -Job $Fixture.Job -Timeout 5 | Out-Null
    Remove-Job -Job $Fixture.Job -Force
}

function Invoke-AcceptanceProcess([string]$BaseUrl, [string]$OutputPath, [switch]$Resume) {
    $arguments = @('-NoProfile','-File',$script,'-BaseUrl',$BaseUrl,'-Codes','TEST_SOURCE',
        '-FromYear','2024','-ToYear','2026','-OutputPath',$OutputPath,
        '-RequestTimeoutSeconds','30','-RunTimeoutMinutes','1')
    if ($Resume) { $arguments += '-Resume' }
    & pwsh @arguments *> (Join-Path $behaviorRoot 'last-run.log')
    return $LASTEXITCODE
}

Push-Location $projectRoot
try {
    Assert-Condition (Test-Path -LiteralPath $script -PathType Leaf) '缺少批量来源验收脚本'

    $scriptText = Get-Content -LiteralPath $script -Raw -Encoding UTF8
    Assert-Condition ($scriptText -match '\[System\.IO\.Path\]::IsPathRooted\(\$Path\)') `
        '验收状态写入必须正确处理绝对路径，不能再次拼接当前目录'
    Assert-Condition ($scriptText -match '\[int\]\$RequestTimeoutSeconds = 1800') `
        '重型省级来源的默认 HTTP 请求超时必须至少覆盖 30 分钟历史回填'
    Assert-Condition ($scriptText -match "\[string\]\`$OutputPath = '\.run/hangzhou-source-acceptance\.json'") `
        '验收报告默认目录必须避开 Maven clean 会删除的 target 目录'
    Assert-Condition ($scriptText -match 'Resolve-OutputPath') `
        '运行模式必须通过统一函数解析绝对和相对 OutputPath'
    Assert-Condition ($scriptText -match "connectionStatus -eq 'FAILED'") `
        '验收必须明确统计并拒绝 FAILED 连接状态'
    Assert-Condition ($scriptText -match 'Test-MeaningfulRun') `
        'PARTIALLY_SUCCEEDED 必须有独立的有效抓取判定'
    Assert-Condition ($scriptText -match 'FIRST_INCREMENTAL_COMPLETED') `
        '首轮增量完成后必须保存阶段检查点'
    Assert-Condition ($scriptText -match 'HISTORICAL_COMPLETED') `
        '历史回填完成后必须保存阶段检查点'
    Assert-Condition ($scriptText -match 'selectedCodes') `
        'Resume 状态必须记录并校验来源选择'

    & $script -ValidateOnly -Codes @('HZ_HRSS_INSTITUTION','HZ_TONGLU_GOV') `
        -FromYear 2024 -ToYear 2026 -OutputPath $testOutput
    Assert-Condition ($LASTEXITCODE -eq 0) '合法参数验证失败'

    & pwsh -NoProfile -File $script -ValidateOnly -Codes 'invalid code' `
        -FromYear 2024 -ToYear 2026 -OutputPath $testOutput *> $null
    Assert-Condition ($LASTEXITCODE -ne 0) '非法来源代码必须失败'

    & pwsh -NoProfile -File $script -ValidateOnly -Codes 'HZ_HRSS_INSTITUTION' `
        -FromYear 2027 -ToYear 2026 -OutputPath $testOutput *> $null
    Assert-Condition ($LASTEXITCODE -ne 0) '倒置年度范围必须失败'

    New-Item -ItemType Directory -Path $behaviorRoot -Force | Out-Null

    $passOutput = Join-Path $behaviorRoot 'absolute-pass.json'
    $fixture = Start-AcquisitionFixture -Port (Get-FreeTcpPort) -Mode 'pass'
    try {
        $exitCode = Invoke-AcceptanceProcess -BaseUrl $fixture.BaseUrl -OutputPath $passOutput
        Assert-Condition ($exitCode -eq 0) '使用绝对 OutputPath 的完整验收应成功'
        Assert-Condition (Test-Path -LiteralPath $passOutput -PathType Leaf) '绝对 OutputPath 未生成报告'
        $passState = Get-Content -LiteralPath $passOutput -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-Condition ($passState.results[0].stage -eq 'COMPLETED') '成功验收应保存 COMPLETED 阶段'
        Assert-Condition ($passState.summary.connectionClassified -eq 1 `
            -and $passState.summary.accessClassified -eq 1) '连接和访问状态分类总数必须覆盖全部选中来源'
    } finally { Stop-AcquisitionFixture $fixture }

    $connectionOutput = Join-Path $behaviorRoot 'connection-failed.json'
    $fixture = Start-AcquisitionFixture -Port (Get-FreeTcpPort) -Mode 'connection-failed'
    try {
        $exitCode = Invoke-AcceptanceProcess -BaseUrl $fixture.BaseUrl -OutputPath $connectionOutput
        Assert-Condition ($exitCode -ne 0) 'connectionStatus FAILED 必须使验收失败'
        $connectionState = Get-Content -LiteralPath $connectionOutput -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-Condition ($connectionState.summary.connectionFailed -eq 1 `
            -and $connectionState.summary.connectionClassified -eq 1) 'FAILED 连接状态必须进入完整分类汇总'
    } finally { Stop-AcquisitionFixture $fixture }

    $partialOutput = Join-Path $behaviorRoot 'partial-zero.json'
    $fixture = Start-AcquisitionFixture -Port (Get-FreeTcpPort) -Mode 'partial-zero'
    try {
        $exitCode = Invoke-AcceptanceProcess -BaseUrl $fixture.BaseUrl -OutputPath $partialOutput
        Assert-Condition ($exitCode -ne 0) '全部条目抓取失败的 PARTIALLY_SUCCEEDED 不能通过验收'
        $partialState = Get-Content -LiteralPath $partialOutput -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-Condition ($partialState.results[0].stage -eq 'NOT_STARTED' `
            -and $partialState.results[0].status -eq 'FAIL') '无成功抓取时不能推进首轮增量阶段'
    } finally { Stop-AcquisitionFixture $fixture }

    $resumeOutput = Join-Path $behaviorRoot 'resume.json'
    $fixture = Start-AcquisitionFixture -Port (Get-FreeTcpPort) -Mode 'resume'
    try {
        $firstExit = Invoke-AcceptanceProcess -BaseUrl $fixture.BaseUrl -OutputPath $resumeOutput
        Assert-Condition ($firstExit -ne 0) '假服务首次历史回填失败应导致验收失败'
        $interrupted = Get-Content -LiteralPath $resumeOutput -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-Condition ($interrupted.results[0].stage -eq 'FIRST_INCREMENTAL_COMPLETED' `
            -and $null -ne $interrupted.results[0].firstIncremental) '中断时必须保留已完成的首轮增量检查点'

        $secondExit = Invoke-AcceptanceProcess -BaseUrl $fixture.BaseUrl -OutputPath $resumeOutput -Resume
        Assert-Condition ($secondExit -eq 0) 'Resume 应从历史回填阶段继续并完成验收'
        $stats = Invoke-RestMethod -Uri "$($fixture.BaseUrl)/stats" -TimeoutSec 2
        Assert-Condition ($stats.incrementalCalls -eq 2 -and $stats.historicalCalls -eq 2) `
            'Resume 不应重跑已完成的首轮增量'
        $resumed = Get-Content -LiteralPath $resumeOutput -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-Condition ($resumed.results[0].stage -eq 'COMPLETED' `
            -and $resumed.results[0].status -eq 'PASS') 'Resume 后应产生完整的来源完成状态'

        $resumed.selectedCodes = @('OTHER_SOURCE')
        $resumed | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $resumeOutput -Encoding UTF8
        $selectionExit = Invoke-AcceptanceProcess -BaseUrl $fixture.BaseUrl -OutputPath $resumeOutput -Resume
        Assert-Condition ($selectionExit -ne 0) 'Resume 必须拒绝与当前 Codes 不一致的选择状态'
    } finally { Stop-AcquisitionFixture $fixture }

    Write-Host '杭州来源批量验收脚本参数契约通过。'
} finally {
    if (Test-Path -LiteralPath $testOutput) {
        Remove-Item -LiteralPath $testOutput -Force
    }
    if (Test-Path -LiteralPath $behaviorRoot -PathType Container) {
        Get-ChildItem -LiteralPath $behaviorRoot -File | Remove-Item -Force
        Remove-Item -LiteralPath $behaviorRoot -Force
    }
    Pop-Location
}
