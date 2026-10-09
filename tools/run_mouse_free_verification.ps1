param(
    [ValidateSet('all', 'navigation', 'field', 'combat', 'combat_cas', 'adaptive_combat', 'base_laser', 'firing_routes', 'regression',
        'ui', 'ui_capture', 'salvage', 'salvage_service', 'salvage_vertical', 'salvage_clearance',
        'salvage_protected', 'salvage_late_engineer', 'solar_service', 'dock_exhaustion',
        'dock_allocation', 'carrier')]
    [string]$Scenario = 'regression',
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(960, 3840)][int]$Width = 1280,
    [ValidateRange(540, 2160)][int]$Height = 720,
    [switch]$ShowGameLog
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'A Java 25 JDK is required. Set JAVA_HOME or pass -JavaHome.'
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportDir = Join-Path $root 'build/verification/mouse-free'
$report = Join-Path $reportDir "$stamp-$Scenario.json"
$log = Join-Path $reportDir "$stamp-$Scenario.log"
$screenshotDir = Join-Path $reportDir "$stamp-$Scenario-${Width}x${Height}-screens"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null

# The complete suite must not share mutable Minecraft world state between
# unrelated fixtures. Run each feature scope in its own game process and
# aggregate the reports here. This also makes a failure directly reproducible
# with the printed child scenario command.
if ($Scenario -eq 'all') {
    $suite = @(
        'navigation',
        'field',
        'combat',
        'combat_cas',
        'adaptive_combat',
        'base_laser',
        'firing_routes',
        'regression',
        'salvage',
        'solar_service',
        'dock_exhaustion',
        'dock_allocation',
        'carrier'
    )
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $children = @()
    $failed = @()
    foreach ($child in $suite) {
        Write-Host "Morrowgear isolated suite: $child"
        & $PSCommandPath -Scenario $child -JavaHome $JavaHome -Width $Width -Height $Height
        $exitCode = $LASTEXITCODE
        $childReport = Get-ChildItem -LiteralPath $reportDir -Filter "*-$child.json" |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        $childResult = if ($childReport) {
            Get-Content -Raw -LiteralPath $childReport.FullName | ConvertFrom-Json
        } else { $null }
        $children += [ordered]@{
            scenario = $child
            status = if ($childResult) { $childResult.status } else { 'missing-report' }
            detail = if ($childResult) { $childResult.detail } else { 'child report was not produced' }
            report = if ($childReport) { $childReport.FullName } else { $null }
        }
        if ($exitCode -ne 0 -or -not $childResult -or $childResult.status -ne 'passed') {
            $failed += $child
        }
    }
    $timer.Stop()
    $aggregate = [ordered]@{
        scenario = 'all'
        status = if ($failed.Count -eq 0) { 'passed' } else { 'failed' }
        detail = if ($failed.Count -eq 0) {
            "ISOLATED SUITE PASS $($suite.Count)/$($suite.Count)"
        } else {
            "ISOLATED SUITE FAIL $($failed.Count)/$($suite.Count): $($failed -join ', ')"
        }
        durationSeconds = [Math]::Round($timer.Elapsed.TotalSeconds, 1)
        children = $children
        finishedAt = [DateTime]::UtcNow.ToString('o')
    }
    $aggregate | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $report -Encoding utf8
    Write-Host $aggregate.detail
    Write-Host "Report: $report"
    if ($failed.Count -gt 0) { exit 1 }
    exit 0
}

$oldScenario = $env:MORROWGEAR_AUTOMATION_SCENARIO
$oldReport = $env:MORROWGEAR_AUTOMATION_REPORT
$oldJavaHome = $env:JAVA_HOME
$oldScreenshotDir = $env:MORROWGEAR_AUTOMATION_SCREENSHOT_DIR
$oldWidth = $env:MORROWGEAR_AUTOMATION_WIDTH
$oldHeight = $env:MORROWGEAR_AUTOMATION_HEIGHT
$env:MORROWGEAR_AUTOMATION_SCENARIO = $Scenario
$env:MORROWGEAR_AUTOMATION_REPORT = $report
$env:MORROWGEAR_AUTOMATION_SCREENSHOT_DIR = $screenshotDir
$env:MORROWGEAR_AUTOMATION_WIDTH = $Width
$env:MORROWGEAR_AUTOMATION_HEIGHT = $Height
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome/bin;$env:Path"

$task = if ($Scenario -eq 'carrier') { 'runAutomatedCarrier' } else { 'runAutomatedDrone' }
$timer = [System.Diagnostics.Stopwatch]::StartNew()
try {
    Set-Location -LiteralPath $root
    Write-Host "Morrowgear mouse-free verification starting: $Scenario"
    # Gradle/Minecraft may write harmless compiler or OpenAL diagnostics to stderr.
    # Preserve them in the log and judge the native process by its exit code/report.
    $nativeErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    if ($ShowGameLog) {
        & ./gradlew.bat $task -x syncLauncherMod --console=plain
    } else {
        & ./gradlew.bat $task -x syncLauncherMod --console=plain *> $log
    }
    $gradleExit = $LASTEXITCODE
    $ErrorActionPreference = $nativeErrorPreference
} finally {
    $timer.Stop()
    $env:MORROWGEAR_AUTOMATION_SCENARIO = $oldScenario
    $env:MORROWGEAR_AUTOMATION_REPORT = $oldReport
    $env:JAVA_HOME = $oldJavaHome
    $env:MORROWGEAR_AUTOMATION_SCREENSHOT_DIR = $oldScreenshotDir
    $env:MORROWGEAR_AUTOMATION_WIDTH = $oldWidth
    $env:MORROWGEAR_AUTOMATION_HEIGHT = $oldHeight
}

if ($gradleExit -ne 0) {
    if (-not $ShowGameLog -and (Test-Path -LiteralPath $log)) {
        Get-Content -LiteralPath $log -Tail 80 | Write-Host
    }
    throw "Minecraft automation process failed with exit code $gradleExit. Inspect the run log."
}
if (-not (Test-Path -LiteralPath $report)) {
    throw "Automation ended without a report: $report"
}
$result = Get-Content -Raw -LiteralPath $report | ConvertFrom-Json
$contactSheet = $null
if ($Scenario -eq 'ui_capture' -and $result.status -eq 'passed') {
    $shots = @(Get-ChildItem -LiteralPath $screenshotDir -Filter '*.png' | Sort-Object Name)
    if ($shots.Count -ne 11) { throw "Expected 11 HMI screenshots but found $($shots.Count): $screenshotDir" }
    Add-Type -AssemblyName System.Drawing
    $thumbWidth, $thumbHeight, $labelHeight, $columns = 400, 225, 24, 3
    $rows = [Math]::Ceiling($shots.Count / $columns)
    $canvas = New-Object System.Drawing.Bitmap ($columns * $thumbWidth), ($rows * ($thumbHeight + $labelHeight))
    $graphics = [System.Drawing.Graphics]::FromImage($canvas)
    $graphics.Clear([System.Drawing.Color]::FromArgb(18, 21, 20))
    $font = New-Object System.Drawing.Font 'Segoe UI', 10
    $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(220, 238, 235))
    try {
        for ($i = 0; $i -lt $shots.Count; $i++) {
            $image = [System.Drawing.Image]::FromFile($shots[$i].FullName)
            try {
                $x = ($i % $columns) * $thumbWidth
                $y = [Math]::Floor($i / $columns) * ($thumbHeight + $labelHeight)
                $graphics.DrawImage($image, $x, $y, $thumbWidth, $thumbHeight)
                $graphics.DrawString($shots[$i].BaseName, $font, $brush, $x + 5, $y + $thumbHeight + 4)
            } finally { $image.Dispose() }
        }
        $contactSheet = Join-Path $reportDir "$stamp-$Scenario-${Width}x${Height}-contact-sheet.png"
        $canvas.Save($contactSheet, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $brush.Dispose(); $font.Dispose(); $graphics.Dispose(); $canvas.Dispose()
    }
}
$seconds = [Math]::Round($timer.Elapsed.TotalSeconds, 1)
Write-Host "Morrowgear mouse-free verification: $($result.status) / $Scenario / ${seconds}s"
Write-Host $result.detail
Write-Host "Report: $report"
Write-Host "Log: $log"
if ($contactSheet) {
    Write-Host "Screenshots: $screenshotDir"
    Write-Host "Contact sheet: $contactSheet"
}
if ($result.status -ne 'passed') { exit 1 }
