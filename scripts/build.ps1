[CmdletBinding()]
param(
    [ValidateRange(1, 64)]
    [int]$CpuCount = [Environment]::ProcessorCount
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'
$defaultJavaHome = 'C:\Program Files\Android\Android Studio\jbr'
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { $defaultJavaHome }
$javaExecutable = Join-Path $javaHome 'bin\java.exe'

if (-not (Test-Path -LiteralPath $javaExecutable)) {
    throw "JDK 21 not found at $javaHome. Set JAVA_HOME before running this script."
}

& (Join-Path $PSScriptRoot 'bootstrap.ps1')

$process = Get-Process -Id $PID
$previousPriority = $process.PriorityClass
$previousJavaHome = $env:JAVA_HOME
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS

try {
    $process.PriorityClass = 'BelowNormal'
    $env:JAVA_HOME = $javaHome
    $env:JAVA_TOOL_OPTIONS = "-XX:ActiveProcessorCount=$CpuCount -XX:CICompilerCount=2"

    & $gradleWrapper --no-daemon "--max-workers=$CpuCount" checkLegacyAbi buildAndroid
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    $process.PriorityClass = $previousPriority
}
