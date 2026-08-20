[CmdletBinding()]
param(
    [switch]$NoBrowser,
    [switch]$Rebuild,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $projectRoot 'compose.yaml'
$jarPath = Join-Path $projectRoot 'career-web\target\career-web-0.1.0-SNAPSHOT.jar'
$runDirectory = Join-Path $projectRoot '.run'
$pidFile = Join-Path $runDirectory 'career-os.pid'
$stdoutFile = Join-Path $runDirectory 'career-os.out.log'
$stderrFile = Join-Path $runDirectory 'career-os.err.log'
$healthUrl = 'http://localhost:8080/actuator/health'
$databasePort = if ($env:CAREER_OS_DB_PORT) { $env:CAREER_OS_DB_PORT } else { '55432' }

function Resolve-Java21 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $bundled = Get-ChildItem (Join-Path $projectRoot '.tooling\temurin-21') -Filter java.exe -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($bundled) { $candidates += $bundled.FullName }
    $systemJava = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($systemJava) { $candidates += $systemJava.Source }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        $versionOutput = (& $candidate -version 2>&1 | Out-String)
        if ($versionOutput -match 'version "21\.' -or $versionOutput -match 'openjdk 21\.') {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw '没有找到 Java 21。请安装 Java 21，或将 JAVA_HOME 指向 Java 21。'
}

function Resolve-Maven {
    $command = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $knownPath = 'D:\Program Files\apache-maven-3.9.11\bin\mvn.cmd'
    if (Test-Path -LiteralPath $knownPath -PathType Leaf) { return $knownPath }
    throw '没有找到 Maven 3.9+。请安装 Maven 并加入 PATH。'
}

function Test-ApplicationRunning {
    try {
        $response = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2
        return $response.status -eq 'UP'
    } catch {
        return $false
    }
}

function Test-ApplicationBuildRequired {
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { return $true }
    $jarTimestamp = (Get-Item -LiteralPath $jarPath).LastWriteTimeUtc
    $sourceRoots = @(
        'career-domain', 'career-application', 'career-infrastructure', 'career-web', 'career-ui'
    ) | ForEach-Object { Join-Path $projectRoot $_ }
    $inputs = @(Get-Item -LiteralPath (Join-Path $projectRoot 'pom.xml')) + @(Get-ChildItem -Path $sourceRoots -File -Recurse)
    $newerInput = $inputs |
        Where-Object {
            $_.FullName -notmatch '\\(target|node_modules|dist)\\' -and
            $_.Extension -in @('.java', '.xml', '.yml', '.yaml', '.ts', '.tsx', '.css', '.json', '.html') -and
            $_.LastWriteTimeUtc -gt $jarTimestamp
        } |
        Select-Object -First 1
    return $null -ne $newerInput
}

if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) { throw "缺少运行配置：$composeFile" }
$java = Resolve-Java21
$env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
if ($ValidateOnly) {
    [void](Resolve-Maven)
    docker compose -f $composeFile config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 配置无效。' }
    Write-Host '本地运行依赖检查通过：Docker Compose 配置、Java 21 和 Maven 均可用。'
    exit 0
}

if (Test-ApplicationRunning) {
    if (-not $Rebuild -and -not (Test-ApplicationBuildRequired)) {
        Write-Host 'Career OS 已在运行：http://localhost:8080/'
        if (-not $NoBrowser) { Start-Process 'http://localhost:8080/' }
        exit 0
    }
    & (Join-Path $PSScriptRoot 'stop-career-os.ps1') -KeepDatabaseRunning
    if ($LASTEXITCODE -ne 0) { throw '旧版 Career OS 未能安全停止。' }
}

Push-Location $projectRoot
try {
    docker compose -f $composeFile up -d postgres
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 容器启动失败。请确认 Docker Desktop 已启动。' }

    $databaseReady = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        docker compose -f $composeFile exec -T postgres pg_isready -U career_os -d career_os *> $null
        if ($LASTEXITCODE -eq 0) { $databaseReady = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $databaseReady) { throw 'PostgreSQL 在 60 秒内没有就绪。' }

    if ($Rebuild -or (Test-ApplicationBuildRequired)) {
        $maven = Resolve-Maven
        & $maven -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Career OS 构建失败。' }
    }

    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
    $env:CAREER_OS_DB_URL = "jdbc:postgresql://localhost:$databasePort/career_os"
    $env:CAREER_OS_DB_USER = 'career_os'
    $env:CAREER_OS_DB_PASSWORD = 'career_os'
    $quotedJarPath = '"' + $jarPath + '"'
    $process = Start-Process -FilePath $java -ArgumentList @('-jar', $quotedJarPath) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile -PassThru
    Set-Content -LiteralPath $pidFile -Value $process.Id -Encoding ascii

    $applicationReady = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        if (Test-ApplicationRunning) { $applicationReady = $true; break }
        if ($process.HasExited) { throw "Career OS 启动失败，请查看 $stderrFile" }
        Start-Sleep -Seconds 2
    }
    if (-not $applicationReady) { throw "Career OS 在 90 秒内没有就绪，请查看 $stderrFile" }

    Write-Host 'Career OS 已就绪：http://localhost:8080/'
    if (-not $NoBrowser) { Start-Process 'http://localhost:8080/' }
} finally {
    Pop-Location
}
