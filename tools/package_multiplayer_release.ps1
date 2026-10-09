param(
    [string]$OutputDirectory = 'build/distributions',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$properties = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'gradle.properties')) {
    if ($line -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') { $properties[$matches[1]] = $matches[2] }
}

$minecraft = $properties.minecraft_version
$loader = $properties.loader_version
$api = $properties.fabric_api_version
$modVersion = $properties.mod_version
$installer = '1.1.2'
if (-not $minecraft -or -not $loader -or -not $api -or -not $modVersion) {
    throw 'Required versions were not found in gradle.properties.'
}

Push-Location $root
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat clean build -x syncLauncherMod --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
    }

    $modJar = Join-Path $root "build/libs/morrowgear-drone-$modVersion.jar"
    if (-not (Test-Path -LiteralPath $modJar)) { throw "Built mod JAR was not found: $modJar" }

    $outputRoot = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
        [IO.Path]::GetFullPath($OutputDirectory)
    } else {
        [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
    }
    New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

    $packageName = "Morrowgear-Drone-Command-$modVersion-MC$minecraft-Fabric"
    $staging = [IO.Path]::GetFullPath((Join-Path $root "build/package-staging/$packageName"))
    $stagingParent = [IO.Path]::GetFullPath((Join-Path $root 'build/package-staging'))
    if (-not $staging.StartsWith($stagingParent, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe staging path: $staging"
    }
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }

    $server = Join-Path $staging 'SERVER'
    $serverMods = Join-Path $server 'mods'
    $clientMods = Join-Path $staging 'CLIENT/mods'
    $documents = Join-Path $staging 'DOCUMENTS'
    New-Item -ItemType Directory -Force -Path $serverMods, $clientMods, $documents | Out-Null

    $apiCache = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/$api"
    $apiJar = Get-ChildItem -LiteralPath $apiCache -Recurse -File -Filter "fabric-api-$api.jar" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $apiJar) {
        $downloadCache = Join-Path $root 'build/package-downloads'
        New-Item -ItemType Directory -Force -Path $downloadCache | Out-Null
        $apiJar = Join-Path $downloadCache "fabric-api-$api.jar"
        $encodedApi = $api -replace '\+', '%2B'
        Invoke-WebRequest -Uri "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$encodedApi/fabric-api-$api.jar" -OutFile $apiJar
    }

    Copy-Item -LiteralPath $modJar -Destination (Join-Path $serverMods "morrowgear-drone-$modVersion.jar")
    Copy-Item -LiteralPath $modJar -Destination (Join-Path $clientMods "morrowgear-drone-$modVersion.jar")
    Copy-Item -LiteralPath $apiJar -Destination (Join-Path $serverMods "fabric-api-$api.jar")
    Copy-Item -LiteralPath $apiJar -Destination (Join-Path $clientMods "fabric-api-$api.jar")

    $launcherName = "fabric-server-mc.$minecraft-loader.$loader-launcher.$installer.jar"
    $launcher = Join-Path $server $launcherName
    $launcherUri = "https://meta.fabricmc.net/v2/versions/loader/$minecraft/$loader/$installer/server/jar"
    Invoke-WebRequest -Uri $launcherUri -OutFile $launcher

    $templates = Join-Path $root 'docs/distribution/templates'
    Copy-Item -LiteralPath (Join-Path $templates 'start-server.bat') -Destination $server
    Copy-Item -LiteralPath (Join-Path $templates 'start-server.sh') -Destination $server
    Copy-Item -LiteralPath (Join-Path $templates 'server.properties') -Destination $server
    Copy-Item -LiteralPath (Join-Path $templates 'eula.txt') -Destination $server
    Copy-Item -LiteralPath (Join-Path $templates 'README_FIRST_JA.txt') -Destination $staging
    Copy-Item -LiteralPath (Join-Path $templates 'VERIFY_PACKAGE.ps1') -Destination $staging
    Copy-Item -LiteralPath (Join-Path $root 'docs/distribution/MORROWGEAR_MULTIPLAYER_SETUP_JA.md') -Destination $documents
    Copy-Item -LiteralPath (Join-Path $root 'docs/distribution/MORROWGEAR_SERVER_OPERATIONS_JA.md') -Destination $documents
    Copy-Item -LiteralPath (Join-Path $root 'LICENSE') -Destination (Join-Path $documents 'LICENSE-MORROWGEAR.txt')

    $manifest = [ordered]@{
        name = 'Morrowgear: Drone Command multiplayer test package'
        minecraft = $minecraft
        javaMinimum = 25
        fabricLoader = $loader
        fabricInstaller = $installer
        fabricApi = $api
        morrowgear = $modVersion
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $staging 'PACKAGE_MANIFEST.json') -Encoding utf8

    $hashLines = Get-ChildItem -LiteralPath $staging -Recurse -File |
        Where-Object { $_.Extension -eq '.jar' } |
        Sort-Object FullName |
        ForEach-Object {
            $relative = [IO.Path]::GetRelativePath($staging, $_.FullName).Replace('\', '/')
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $relative"
        }
    $hashLines | Set-Content -LiteralPath (Join-Path $staging 'SHA256SUMS.txt') -Encoding ascii

    $zip = Join-Path $outputRoot "$packageName.zip"
    if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
    Compress-Archive -Path $staging -DestinationPath $zip -CompressionLevel Optimal

    Write-Host "Package: $zip"
    Write-Host "Staging: $staging"
} finally {
    Pop-Location
}
