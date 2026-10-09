param(
    [string]$ServerAddress = '127.0.0.1',
    [ValidateRange(1, 65535)][int]$Port = 25565,
    [ValidateLength(1, 16)][string]$Username = 'MGVerifier'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportDir = Join-Path $root 'build/verification/multiplayer'
$report = Join-Path $reportDir "$stamp-connect.json"
$log = Join-Path $reportDir "$stamp-connect.log"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null

$oldScenario = $env:MORROWGEAR_AUTOMATION_SCENARIO
$oldReport = $env:MORROWGEAR_AUTOMATION_REPORT
$env:MORROWGEAR_AUTOMATION_SCENARIO = 'multiplayer_connect'
$env:MORROWGEAR_AUTOMATION_REPORT = $report

try {
    Push-Location $root
    $nativeErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat runClient -x syncLauncherMod --console=plain `
        --args="--quickPlayMultiplayer ${ServerAddress}:$Port --username $Username --width 960 --height 540" *> $log
    $gradleExit = $LASTEXITCODE
    $ErrorActionPreference = $nativeErrorPreference
} finally {
    Pop-Location
    $env:MORROWGEAR_AUTOMATION_SCENARIO = $oldScenario
    $env:MORROWGEAR_AUTOMATION_REPORT = $oldReport
}

if ($gradleExit -ne 0) {
    if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 100 | Write-Host }
    throw "Multiplayer client process failed with exit code $gradleExit."
}
if (-not (Test-Path -LiteralPath $report)) {
    if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 100 | Write-Host }
    throw "Multiplayer client ended without a report: $report"
}

$result = Get-Content -Raw -LiteralPath $report | ConvertFrom-Json
Write-Host "Morrowgear multiplayer verification: $($result.status)"
Write-Host $result.detail
Write-Host "Report: $report"
Write-Host "Log: $log"
if ($result.status -ne 'passed') { exit 1 }
