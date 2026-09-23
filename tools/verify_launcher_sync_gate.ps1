param([string]$JavaHome = 'C:/Program Files/Eclipse Adoptium/jdk-25.0.4.7-hotspot')

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$probe = Join-Path $PSScriptRoot 'launcher-sync-gate-probe.init.gradle'
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome/bin;$env:Path"
$installed = Join-Path $env:APPDATA '.minecraft/morrowgear/mods/morrowgear-drone-latest.jar'
$pending = Join-Path $env:APPDATA '.minecraft/morrowgear/mods/.morrowgear-drone-pending.jar.tmp'

function Get-ArtifactHash([string]$Path) {
    if (Test-Path -LiteralPath $Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
    return 'ABSENT'
}

$before = Get-ArtifactHash $installed
$beforePending = Get-ArtifactHash $pending
$cases = @(
    @{ Name = 'complete verification'; Args = @('syncLauncherMod', '-Plauncher_release_ready=true'); Exit = 0; Allow = $true },
    @{ Name = 'unapproved release'; Args = @('syncLauncherMod', '-Plauncher_release_ready=false'); Exit = 0; Allow = $false },
    @{ Name = 'check excluded'; Args = @('syncLauncherMod', '-x', 'check', '-Plauncher_release_ready=true'); Exit = 0; Allow = $false },
    @{ Name = 'test excluded'; Args = @('syncLauncherMod', '-x', 'test', '-Plauncher_release_ready=true'); Exit = 0; Allow = $false },
    @{ Name = 'partial tests'; Args = @('test', '--tests', 'jp.morrowgear.drone.MicroMissilePolicyTest', 'syncLauncherMod', '-Plauncher_release_ready=true'); Exit = 0; Allow = $false },
    @{ Name = 'failed build'; Args = @('build', '-PmorrowgearProbeFailure', '--continue', '-Plauncher_release_ready=true'); Exit = 1; Allow = $false }
)

Push-Location $repo
try {
    foreach ($case in $cases) {
        # The init script removes every installer action before the task can run.
        $gradleArgs = @($case.Args) + @('-I', $probe, '--console=plain')
        $output = (& ./gradlew.bat @gradleArgs 2>&1 | Out-String)
        $code = $LASTEXITCODE
        $allowed = $output.Contains('MORROWGEAR_SYNC_PROBE_ALLOWED_NO_INSTALL')
        if ($code -ne $case.Exit -or $allowed -ne $case.Allow) {
            Write-Output $output
            throw "Gate case failed: $($case.Name); exit=$code, allowed=$allowed"
        }
        if ($case.Name -eq 'failed build' -and !$output.Contains('Expected launcher verification gate probe failure')) {
            throw 'The failure probe failed for an unexpected reason.'
        }
        if ((Get-ArtifactHash $installed) -ne $before -or (Get-ArtifactHash $pending) -ne $beforePending) {
            throw 'Installed or pending artifact unexpectedly changed.'
        }
        Write-Output "PASS: $($case.Name); no installation"
    }
    Write-Output "PASS: all 6 launcher gates; installed SHA256=$before; pending=$beforePending"
} finally {
    Pop-Location
}
