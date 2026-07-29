[CmdletBinding()]
param(
    [switch]$CliOnly
)

$ErrorActionPreference = 'Stop'

$cliVersion = '6.0.0'
$expectedSha256 = 'C25549BC17D59D2EB94FA5F86E60E9B77A02772CA88F7050F8F1276F923A9958'
$downloadUrl = "https://github.com/ReVanced/revanced-cli/releases/download/v$cliVersion/revanced-cli-$cliVersion-all.jar"
$gradlePluginRepository = 'https://github.com/ReVanced/revanced-patches-gradle-plugin.git'
$gradlePluginCommit = 'e810f1fab1da24b67b70234c839c6b83c62c4064'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localDirectory = Join-Path $projectRoot 'local'
$patcherJar = Join-Path $localDirectory 'patcher-22.0.0.jar'
$gradlePluginDirectory = Join-Path $localDirectory 'revanced-patches-gradle-plugin'

function Test-PatcherJar {
    param([Parameter(Mandatory)][string]$Path)

    (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash -eq $expectedSha256
}

if (Test-Path -LiteralPath $patcherJar) {
    if (-not (Test-PatcherJar -Path $patcherJar)) {
        throw "Unexpected SHA-256 for existing file: $patcherJar"
    }

    Write-Host "ReVanced CLI $cliVersion is already verified."
}
else {
    New-Item -ItemType Directory -Force -Path $localDirectory | Out-Null
    $temporaryJar = "$patcherJar.download"

    try {
        Invoke-WebRequest -Uri $downloadUrl -OutFile $temporaryJar

        if (-not (Test-PatcherJar -Path $temporaryJar)) {
            throw "SHA-256 verification failed for $downloadUrl"
        }

        Move-Item -LiteralPath $temporaryJar -Destination $patcherJar
        Write-Host "Downloaded and verified ReVanced CLI $cliVersion."
    }
    finally {
        if (Test-Path -LiteralPath $temporaryJar) {
            Remove-Item -LiteralPath $temporaryJar
        }
    }
}

if ($CliOnly) {
    return
}

if (-not (Test-Path -LiteralPath (Join-Path $gradlePluginDirectory '.git'))) {
    if (Test-Path -LiteralPath $gradlePluginDirectory) {
        throw "Unexpected non-Git path: $gradlePluginDirectory"
    }

    & git clone --filter=blob:none --no-checkout $gradlePluginRepository $gradlePluginDirectory
    if ($LASTEXITCODE -ne 0) {
        throw "Could not clone $gradlePluginRepository."
    }
}

$pluginOrigin = (& git -C $gradlePluginDirectory remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0 -or $pluginOrigin -ne $gradlePluginRepository) {
    throw "Unexpected ReVanced Gradle plugin origin: $pluginOrigin"
}

$pluginHasIndex = Test-Path -LiteralPath (Join-Path $gradlePluginDirectory '.git\index')
$pluginHead = (& git -C $gradlePluginDirectory rev-parse HEAD 2>$null).Trim()
if (-not $pluginHasIndex -or $pluginHead -ne $gradlePluginCommit) {
    if ($pluginHasIndex) {
        $pluginStatus = & git -C $gradlePluginDirectory status --short
        if ($LASTEXITCODE -ne 0) {
            throw "Could not inspect $gradlePluginDirectory."
        }
        if ($pluginStatus) {
            throw "ReVanced Gradle plugin checkout is dirty: $gradlePluginDirectory"
        }
    }

    & git -C $gradlePluginDirectory fetch --depth=1 origin $gradlePluginCommit
    if ($LASTEXITCODE -ne 0) {
        throw "Could not fetch ReVanced Gradle plugin commit $gradlePluginCommit."
    }

    & git -C $gradlePluginDirectory checkout --detach $gradlePluginCommit
    if ($LASTEXITCODE -ne 0) {
        throw "Could not checkout ReVanced Gradle plugin commit $gradlePluginCommit."
    }
}

$pluginStatus = & git -C $gradlePluginDirectory status --short
if ($LASTEXITCODE -ne 0 -or $pluginStatus) {
    throw "ReVanced Gradle plugin checkout is not clean: $gradlePluginDirectory"
}

$bun = Get-Command bun.exe -ErrorAction SilentlyContinue
if (-not $bun) {
    throw 'Bun is required to prepare the bundled DevTools frontend.'
}

& $bun.Source (Join-Path $PSScriptRoot 'build-devtools-frontend.ts')
if ($LASTEXITCODE -ne 0) {
    throw "DevTools frontend preparation failed with exit code $LASTEXITCODE."
}
