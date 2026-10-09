$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sumFile = Join-Path $root 'SHA256SUMS.txt'
if (-not (Test-Path -LiteralPath $sumFile)) { throw 'SHA256SUMS.txt was not found.' }

$failures = @()
foreach ($line in Get-Content -LiteralPath $sumFile) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $hash, $relative = $line -split '  ', 2
    $path = Join-Path $root ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $path)) {
        $failures += "MISSING: $relative"
        continue
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $hash.ToLowerInvariant()) { $failures += "MISMATCH: $relative" }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    throw 'Package verification failed.'
}
Write-Host 'Morrowgear package verification passed.' -ForegroundColor Green

