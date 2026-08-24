param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$CandidateId = '01992f09-0000-7000-8000-000000000001',
    [int]$TargetYear = 2027,
    [string]$AsOf = '2026-08-24'
)

$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "ACCEPTANCE FAILED: $Message" }
}

function Get-Json {
    param([string]$Path)
    $client = New-Object System.Net.WebClient
    try {
        $client.Headers['Accept'] = 'application/json'
        $bytes = $client.DownloadData("$base$Path")
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        $text | ConvertFrom-Json
    } finally {
        $client.Dispose()
    }
}

$plan = Get-Json "/api/v1/candidates/$CandidateId/career-plan?targetYear=$TargetYear&asOf=$AsOf"
Assert-True ($plan.currentScenario.code -eq 'MASTER_IN_PROGRESS') "currentScenario.code was '$($plan.currentScenario.code)'"
Assert-True ($plan.graduateTrack.code -eq 'TARGET_YEAR_GRADUATE') "graduateTrack.code was '$($plan.graduateTrack.code)'"
Assert-True ($plan.targetYear -eq 2027) "targetYear was '$($plan.targetYear)'"
Assert-True ($plan.algorithmVersion -eq 'career-plan-v3') "algorithmVersion was '$($plan.algorithmVersion)'"
Assert-True ($null -ne $plan.configuredCoverage) 'configuredCoverage is missing'
Assert-True ($null -ne $plan.targetMarketCoverage) 'targetMarketCoverage is missing'
Assert-True ($null -ne $plan.analysisCoverage) 'analysisCoverage is missing'
Assert-True ($plan.targetMarketCoverage.targetCount -ge 20) "target source count was '$($plan.targetMarketCoverage.targetCount)'"
Assert-True (@($plan.jobProjections).Count -eq $plan.analysisCoverage.jobCount) "jobProjections count does not match analyzed jobs"

$notCovered = @($plan.recommendedRoutes | Where-Object { $_.rankingState -eq 'NOT_COVERED' })
Assert-True ($notCovered.Count -gt 0) 'no route is gated as NOT_COVERED'
foreach ($route in $notCovered) {
    Assert-True ($null -eq $route.priorityScore) "NOT_COVERED route '$($route.code)' has score '$($route.priorityScore)'"
}

$representatives = @($plan.recommendedRoutes | ForEach-Object { $_.representativeJobs } | Where-Object { $_.year -eq 2026 })
$distinctProjection = @($representatives | Where-Object {
    $null -ne $_.historicalActual -and $null -ne $_.targetYearAnalog -and
    $_.historicalActual.outcome -ne $_.targetYearAnalog.outcome
})
Assert-True ($distinctProjection.Count -gt 0) 'no 2026 representative job has distinct historicalActual and targetYearAnalog outcomes'

$representativeIds = @($representatives | ForEach-Object { $_.jobId })
$nonRepresentative = @($plan.jobProjections | Where-Object { $representativeIds -notcontains $_.jobId }) | Select-Object -First 1
if ($null -ne $nonRepresentative) {
    Assert-True ($null -ne $nonRepresentative.historicalActual) 'non-representative job has no historicalActual outcome'
    Assert-True ($null -ne $nonRepresentative.targetYearAnalog) 'non-representative job has no targetYearAnalog outcome'
    Assert-True (@($nonRepresentative.scenarioOutcomes).Count -gt 0) 'non-representative job has no scenario outcomes'
}

$processProjection = @($plan.jobProjections) | Select-Object -First 1
if ($null -ne $processProjection) {
    $job = Get-Json "/api/v1/jobs/$($processProjection.jobId)"
    $event = Get-Json "/api/v1/recruitment-events/$($job.recruitmentEventId)"
    Assert-True ($null -ne $event.processFacts) 'recruitment event is missing processFacts'
    foreach ($stage in @('notice','application','qualificationReview','payment','admissionTicket','writtenExam','professionalTest','interview','physicalExam','investigation','publication','appointment')) {
        Assert-True ($null -ne $event.processFacts.$stage.state) "process stage '$stage' has no evidence state"
    }
}

$subjects = @($plan.examSummary.subjects | ForEach-Object { $_.subject })
$careerAptitudeTest = -join ([char[]](32844, 19994, 33021, 21147, 20542, 21521, 27979, 39564))
$comprehensiveApplication = -join ([char[]](32508, 21512, 24212, 29992, 33021, 21147))
Assert-True ($subjects -contains $careerAptitudeTest) 'official historical evidence does not expose the career aptitude test'
Assert-True ($subjects -contains $comprehensiveApplication) 'official historical evidence does not expose comprehensive application ability'

$actions = Get-Json "/api/v1/candidates/$CandidateId/personal-actions?asOf=$AsOf"
$actionIds = @($actions.items | ForEach-Object { $_.id })
$workbench = Get-Json "/api/v1/candidates/$CandidateId/workbench-summary"
$trustedTierCount = $workbench.tierCounts.t1 + $workbench.tierCounts.t2 + $workbench.tierCounts.t3
if ($trustedTierCount -eq 0) {
    Assert-True ($actionIds -contains 'evidence:CONFIRM_MASTER_GRADUATION_MONTH') 'empty trusted pool lost the master graduation action'
    Assert-True ($actionIds -contains 'evidence:VERIFY_MASTER_CREDENTIAL') 'empty trusted pool lost the credential action'
    Assert-True ($actionIds -contains 'preparation:PREPARE_WRITTEN_EXAM_BASELINE') 'empty trusted pool lost the written-exam baseline action'
}

$result = [ordered]@{
    candidateId = $CandidateId
    targetYear = $plan.targetYear
    currentScenario = $plan.currentScenario.code
    graduateTrack = $plan.graduateTrack.code
    targetSources = [ordered]@{
        total = $plan.targetMarketCoverage.targetCount
        connected = $plan.targetMarketCoverage.connected
        partial = $plan.targetMarketCoverage.partial
        failed = $plan.targetMarketCoverage.failed
        notConnected = $plan.targetMarketCoverage.notConnected
    }
    analysis = [ordered]@{
        sources = $plan.analysisCoverage.sourceCount
        events = $plan.analysisCoverage.eventCount
        jobs = $plan.analysisCoverage.jobCount
        evidenceCompleteJobs = $plan.analysisCoverage.evidenceCompleteJobs
        projectedJobs = @($plan.jobProjections).Count
    }
    distinctProjectedJobs = $distinctProjection.Count
    examSubjects = $subjects
    actionIds = $actionIds
    trustedTierCount = $trustedTierCount
    status = 'PASS'
}

$result | ConvertTo-Json -Depth 8
