[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Apk,

    [string]$Output,

    [string]$NewTabUrl = 'http://tabpage.ariex.ru',

    [switch]$SideBySide,

    [ValidateRange(1, 64)]
    [int]$CpuCount = 4
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$cliJar = Join-Path $projectRoot 'local\patcher-22.0.0.jar'
$patchesFile = Join-Path $projectRoot 'patches\build\libs\patches-0.1.0.rvp'
$keystore = Join-Path $projectRoot 'local\edge-mod.keystore'
$inputApk = (Resolve-Path -LiteralPath $Apk).Path
$defaultJavaHome = 'C:\Program Files\Android\Android Studio\jbr'
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { $defaultJavaHome }
$javaExecutable = Join-Path $javaHome 'bin\java.exe'
$androidSdk = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
}
elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
}
else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$androidFrameworkApk = Join-Path $androidSdk 'platforms\android-37.0\android.jar'
$customAapt2 = Join-Path $androidSdk 'build-tools\37.0.0\aapt2.exe'

$parsedNewTabUrl = $null
if (
    -not [Uri]::TryCreate($NewTabUrl, [UriKind]::Absolute, [ref]$parsedNewTabUrl) -or
    $parsedNewTabUrl.Scheme -notin 'http', 'https' -or
    [string]::IsNullOrWhiteSpace($parsedNewTabUrl.Host)
) {
    throw "NewTabUrl must be an absolute HTTP or HTTPS URL."
}

if (-not $Output) {
    $inputDirectory = Split-Path -Parent $inputApk
    $inputName = [IO.Path]::GetFileNameWithoutExtension($inputApk)
    $suffix = if ($SideBySide) { '-revanced-test.apk' } else { '-revanced.apk' }
    $Output = Join-Path $inputDirectory "$inputName$suffix"
}

$outputApk = [IO.Path]::GetFullPath($Output)
if ($outputApk -eq $inputApk) {
    throw "Output must differ from the source APK."
}

$outputDirectory = Split-Path -Parent $outputApk
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

& (Join-Path $PSScriptRoot 'build.ps1') -CpuCount $CpuCount

if (-not (Test-Path -LiteralPath $cliJar)) {
    throw "ReVanced CLI not found at $cliJar."
}
if (-not (Test-Path -LiteralPath $patchesFile)) {
    throw "Built RVP not found at $patchesFile."
}
if (-not (Test-Path -LiteralPath $javaExecutable)) {
    throw "JDK 21 not found at $javaHome. Set JAVA_HOME before running this script."
}
if (-not (Test-Path -LiteralPath $androidFrameworkApk)) {
    throw "Android SDK platform 37.0 not found at $androidFrameworkApk."
}
if (-not (Test-Path -LiteralPath $customAapt2)) {
    throw "Android SDK Build-Tools 37.0.0 not found at $customAapt2."
}

$process = Get-Process -Id $PID
$previousPriority = $process.PriorityClass
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$previousAndroidFrameworkApk = $env:EDGE_REVANCED_ANDROID_FRAMEWORK_APK
$previousAndroidFrameworkDirectory = $env:EDGE_REVANCED_ANDROID_FRAMEWORK_DIRECTORY
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryPrefix = $temporaryRoot.TrimEnd(
    [IO.Path]::DirectorySeparatorChar
) + [IO.Path]::DirectorySeparatorChar
$temporaryFiles = [IO.Path]::GetFullPath(
    (Join-Path $temporaryRoot "edge-revanced-$([Guid]::NewGuid())")
)
if (-not $temporaryFiles.StartsWith(
    $temporaryPrefix,
    [StringComparison]::OrdinalIgnoreCase
)) {
    throw "Unsafe temporary directory: $temporaryFiles"
}

try {
    $process.PriorityClass = 'BelowNormal'
    $env:JAVA_TOOL_OPTIONS = "-XX:ActiveProcessorCount=$CpuCount -XX:CICompilerCount=2"
    $env:EDGE_REVANCED_ANDROID_FRAMEWORK_APK = $androidFrameworkApk
    $env:EDGE_REVANCED_ANDROID_FRAMEWORK_DIRECTORY = Join-Path $temporaryFiles 'patcher'

    $selectedPatches = @(
        'Кнопка DevTools'
        'Своя новая вкладка'
        'Установка расширений из Chrome Web Store'
        'Удобный экран вкладок'
        'Свайп вверх к вкладкам'
        'Без повторяющегося окна аккаунта Microsoft'
    )
    if ($SideBySide) {
        $selectedPatches += 'Отдельная тестовая установка'
    }

    $patchArguments = @(
        '-jar', $cliJar,
        'patch',
        '-p', $patchesFile,
        '-b',
        '--exclusive',
        '--keystore', $keystore,
        '--purge',
        '-t', $temporaryFiles,
        '-o', $outputApk
    )
    $patchArguments += '--custom-aapt2-binary', $customAapt2
    foreach ($patchName in $selectedPatches) {
        $patchArguments += '-e', $patchName
    }
    $patchArguments += '-O', "Адрес новой вкладки=$NewTabUrl", $inputApk

    & $javaExecutable @patchArguments 2>&1 | Tee-Object -Variable patchLog

    $patchExitCode = $LASTEXITCODE
    $failedPatches = @(
        $patchLog | Where-Object { "$_" -match '^SEVERE: ".+" failed:' }
    )
    if ($patchExitCode -ne 0 -or $failedPatches.Count -gt 0) {
        Remove-Item -LiteralPath $outputApk -Force -ErrorAction SilentlyContinue
        throw "ReVanced patching failed; the partial output APK was removed."
    }

    Write-Host "Patched APK: $outputApk"
    Write-Host "Signing key: $keystore"
}
finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    $env:EDGE_REVANCED_ANDROID_FRAMEWORK_APK = $previousAndroidFrameworkApk
    $env:EDGE_REVANCED_ANDROID_FRAMEWORK_DIRECTORY = $previousAndroidFrameworkDirectory
    $process.PriorityClass = $previousPriority
    if (Test-Path -LiteralPath $temporaryFiles) {
        Remove-Item -LiteralPath $temporaryFiles -Recurse -Force
    }
}
