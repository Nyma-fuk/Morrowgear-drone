param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'A Java 25 JDK is required. Set JAVA_HOME or pass -JavaHome.'
}
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome/bin;$env:Path"
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
& ./gradlew.bat runCarrierVerification -x syncLauncherMod --console=plain
exit $LASTEXITCODE
