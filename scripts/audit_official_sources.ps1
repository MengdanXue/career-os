param(
    [string]$CatalogPath = "career-infrastructure/src/main/resources/official-source-catalog.yml",
    [string]$OutputPath = "target/source-audit.json",
    [int]$TimeoutSeconds = 20
)

$ErrorActionPreference = "Stop"

function Read-SourceCatalog([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Catalog not found: $Path"
    }
    $items = [System.Collections.Generic.List[object]]::new()
    $current = $null
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^  - code:\s*(.+?)\s*$') {
            if ($null -ne $current) { $items.Add([pscustomobject]$current) }
            $current = [ordered]@{ Code = $Matches[1].Trim("'`"") }
            continue
        }
        if ($null -eq $current) { continue }
        if ($line -match '^    (name|routeCode|officialRootUrl|listingUrl|strategy):\s*(.*?)\s*$') {
            $key = $Matches[1]
            $value = $Matches[2].Trim("'`"")
            $current[$key] = if ([string]::IsNullOrWhiteSpace($value)) { $null } else { $value }
        }
        if ($line -match '^    enabled:\s*(true|false)\s*$') {
            $current.Enabled = $Matches[1] -eq 'true'
        }
    }
    if ($null -ne $current) { $items.Add([pscustomobject]$current) }
    return $items
}

function Test-RelatedHost([string]$Expected, [string]$Actual) {
    if ([string]::IsNullOrWhiteSpace($Expected) -or [string]::IsNullOrWhiteSpace($Actual)) { return $false }
    $expectedHost = $Expected.ToLowerInvariant()
    $actualHost = $Actual.ToLowerInvariant()
    return $actualHost -eq $expectedHost -or
        $actualHost.EndsWith(".$expectedHost") -or $expectedHost.EndsWith(".$actualHost")
}

try {
    $sources = @(Read-SourceCatalog -Path $CatalogPath)
    if ($sources.Count -lt 20) { throw "Catalog must contain at least 20 sources" }
    $duplicateCodes = $sources | Group-Object Code | Where-Object Count -gt 1
    if ($duplicateCodes) { throw "Duplicate source codes: $($duplicateCodes.Name -join ', ')" }
    foreach ($source in $sources) {
        if (-not $source.Code -or -not $source.officialRootUrl) { throw "Malformed source definition" }
        $root = [uri]$source.officialRootUrl
        if ($root.Scheme -ne 'https' -or -not $root.Host) { throw "Invalid official root for $($source.Code)" }
        if ($source.Enabled -and -not $source.listingUrl) { throw "Enabled source lacks listing URL: $($source.Code)" }
    }
} catch {
    Write-Error $_
    exit 2
}

$results = [System.Collections.Generic.List[object]]::new()
foreach ($source in $sources) {
    $requestedUrl = if ($source.listingUrl) { $source.listingUrl } else { $source.officialRootUrl }
    $startedAt = [DateTimeOffset]::UtcNow
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $requestedUrl -MaximumRedirection 5 -TimeoutSec $TimeoutSeconds `
            -Method Get -Headers @{ 'User-Agent' = 'CareerOS-SourceAudit/1.0 (+read-only)' }
        $finalUri = [uri]$requestedUrl
        if ($null -ne $response.BaseResponse) {
            $baseProperties = @($response.BaseResponse.PSObject.Properties.Name)
            if ($baseProperties -contains 'RequestMessage' -and
                $null -ne $response.BaseResponse.RequestMessage -and
                $null -ne $response.BaseResponse.RequestMessage.RequestUri) {
                $finalUri = $response.BaseResponse.RequestMessage.RequestUri
            } elseif ($baseProperties -contains 'ResponseUri' -and $null -ne $response.BaseResponse.ResponseUri) {
                $finalUri = $response.BaseResponse.ResponseUri
            }
        }
        $rootHost = ([uri]$source.officialRootUrl).Host
        $crossDomain = -not (Test-RelatedHost -Expected $rootHost -Actual $finalUri.Host)
        $statusCode = [int]$response.StatusCode
        $status = if ($crossDomain) { 'CROSS_DOMAIN_REDIRECT' }
            elseif ($statusCode -ge 200 -and $statusCode -lt 300) { 'REACHABLE' }
            else { 'ACCESS_FAILED' }
        $results.Add([pscustomobject][ordered]@{
            code = $source.Code
            routeCode = $source.routeCode
            enabled = [bool]$source.Enabled
            strategy = $source.strategy
            requestedUrl = $requestedUrl
            officialRootUrl = $source.officialRootUrl
            httpStatus = $statusCode
            finalUrl = $finalUri.AbsoluteUri
            finalHost = $finalUri.Host
            contentType = [string]$response.Headers.'Content-Type'
            crossDomainRedirect = $crossDomain
            auditStatus = $status
            error = $null
            auditedAt = $startedAt.ToString('o')
        })
    } catch {
        $httpStatus = $null
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $httpStatus = [int]$_.Exception.Response.StatusCode
        }
        $results.Add([pscustomobject][ordered]@{
            code = $source.Code
            routeCode = $source.routeCode
            enabled = [bool]$source.Enabled
            strategy = $source.strategy
            requestedUrl = $requestedUrl
            officialRootUrl = $source.officialRootUrl
            httpStatus = $httpStatus
            finalUrl = $null
            finalHost = $null
            contentType = $null
            crossDomainRedirect = $false
            auditStatus = 'ACCESS_FAILED'
            error = $_.Exception.Message
            auditedAt = $startedAt.ToString('o')
        })
    }
}

$resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputPath))
$outputDirectory = Split-Path -Parent $resolvedOutput
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
}
$results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resolvedOutput -Encoding UTF8
Write-Output "Audited $($results.Count) official targets -> $resolvedOutput"
exit 0
