[CmdletBinding()]
param(
    [switch]$KeepDatabaseRunning,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $projectRoot 'compose.yaml'
$jarPath = (Join-Path $projectRoot 'career-web\target\career-web-0.1.0-SNAPSHOT.jar')
$pidFile = Join-Path $projectRoot '.run\career-os.pid'

if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) { throw "缺少运行配置：$composeFile" }
if ($ValidateOnly) {
    Write-Host '停止脚本检查通过。它只会停止 PID 文件中且命令行指向当前项目 JAR 的进程。'
    exit 0
}

if (Test-Path -LiteralPath $pidFile -PathType Leaf) {
    $processId = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    $resolvedJar = [System.IO.Path]::GetFullPath($jarPath)
    if ($processInfo -and $processInfo.CommandLine -and $processInfo.CommandLine.Contains($resolvedJar, [System.StringComparison]::OrdinalIgnoreCase)) {
        Stop-Process -Id $processId
        Write-Host 'Career OS 应用已停止。'
    } elseif ($processInfo) {
        throw "拒绝停止 PID $processId：它不是当前项目启动的 Career OS 进程。"
    }
    Remove-Item -LiteralPath $pidFile -Force
} else {
    Write-Host '没有发现正在运行的 Career OS 应用记录。'
}

if (-not $KeepDatabaseRunning) {
    Push-Location $projectRoot
    try {
        docker compose -f $composeFile stop postgres
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 容器停止失败。' }
        Write-Host 'PostgreSQL 已停止，数据卷仍然保留。'
    } finally {
        Pop-Location
    }
}

