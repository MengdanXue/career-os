[CmdletBinding()]
param([switch]$Smoke)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $projectRoot 'compose.yaml'
$startScript = Join-Path $PSScriptRoot 'start-career-os.ps1'
$stopScript = Join-Path $PSScriptRoot 'stop-career-os.ps1'

Push-Location $projectRoot
try {
    docker compose -f $composeFile config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 配置无效。' }

    & $startScript -ValidateOnly
    if ($LASTEXITCODE -ne 0) { throw '启动脚本依赖检查失败。' }
    & $stopScript -ValidateOnly
    if ($LASTEXITCODE -ne 0) { throw '停止脚本安全检查失败。' }

    if ($Smoke) {
        & $startScript -NoBrowser
        $health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 5
        if ($health.status -ne 'UP') { throw '应用健康检查未返回 UP。' }
        Write-Host '本地运行冒烟验证通过：http://localhost:8080/'
    } else {
        Write-Host '本地运行契约验证通过。添加 -Smoke 可实际启动数据库和应用。'
    }
} finally {
    Pop-Location
}

