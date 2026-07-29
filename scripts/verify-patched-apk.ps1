[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Apk,

    [string]$ExpectedNewTabUrl = 'http://tabpage.ariex.ru',

    [string]$Dexdump
)

$ErrorActionPreference = 'Stop'

$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$androidSdk = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
}
elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
}
else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$dexdumpExecutable = if ($Dexdump) {
    (Resolve-Path -LiteralPath $Dexdump).Path
}
else {
    Join-Path $androidSdk 'build-tools\37.0.0\dexdump.exe'
}

if (-not (Test-Path -LiteralPath $dexdumpExecutable)) {
    throw "dexdump was not found at $dexdumpExecutable."
}

$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryPrefix = $temporaryRoot.TrimEnd(
    [IO.Path]::DirectorySeparatorChar
) + [IO.Path]::DirectorySeparatorChar
$temporaryFiles = [IO.Path]::GetFullPath(
    (Join-Path $temporaryRoot "edge-revanced-verify-$([Guid]::NewGuid())")
)
if (-not $temporaryFiles.StartsWith(
    $temporaryPrefix,
    [StringComparison]::OrdinalIgnoreCase
)) {
    throw "Unsafe temporary directory: $temporaryFiles"
}

try {
    New-Item -ItemType Directory -Path $temporaryFiles | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    $matchingDexFiles = @()
    $archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -notmatch '^classes(?:\d+)?\.dex$') {
                continue
            }

            $dexPath = Join-Path $temporaryFiles $entry.Name
            [IO.Compression.ZipFileExtensions]::ExtractToFile(
                $entry,
                $dexPath
            )
            $dexText = [Text.Encoding]::UTF8.GetString(
                [IO.File]::ReadAllBytes($dexPath)
            )
            if ($dexText.Contains($ExpectedNewTabUrl)) {
                $matchingDexFiles += $dexPath
            }
        }
    }
    finally {
        $archive.Dispose()
    }

    if ($matchingDexFiles.Count -ne 1) {
        throw (
            "Expected exactly one DEX containing the new-tab URL, found " +
            "$($matchingDexFiles.Count)."
        )
    }

    $dumpPath = Join-Path $temporaryFiles 'new-tab.dexdump.txt'
    & $dexdumpExecutable -d $matchingDexFiles[0] 2>&1 |
        Set-Content -LiteralPath $dumpPath -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        throw "dexdump failed with exit code $LASTEXITCODE."
    }

    $lines = Get-Content -LiteralPath $dumpPath
    $escapedUrl = [regex]::Escape("`"$ExpectedNewTabUrl`"")
    $markerIndexes = @(
        for ($index = 0; $index -lt $lines.Count; $index++) {
            if (
                $lines[$index] -match
                "\|\d{4}:\s+const-string(?:/jumbo)?\s+v\d+,\s+$escapedUrl"
            ) {
                $index
            }
        }
    )
    if ($markerIndexes.Count -ne 1) {
        throw (
            "Expected one executable new-tab URL marker, found " +
            "$($markerIndexes.Count)."
        )
    }

    $markerIndex = $markerIndexes[0]
    $methodStart = $markerIndex
    while (
        $methodStart -ge 0 -and
        $lines[$methodStart] -notmatch
        '^\s+#\d+\s+:\s+\(in (?<class>L[^;]+;)\)'
    ) {
        $methodStart--
    }
    if ($methodStart -lt 0) {
        throw 'Could not identify the method containing the new-tab URL.'
    }
    $classDescriptor = $Matches.class

    $methodEnd = $methodStart + 1
    while (
        $methodEnd -lt $lines.Count -and
        $lines[$methodEnd] -notmatch '^\s+#\d+\s+:\s+\(in L[^;]+;\)' -and
        $lines[$methodEnd] -notmatch '^\s+source_file_idx\s+:'
    ) {
        $methodEnd++
    }
    $methodLines = @($lines[$methodStart..($methodEnd - 1)])
    $methodText = $methodLines -join "`n"

    if ($methodText -notmatch "(?m)^\s+name\s+:\s+'(?<name>[^']+)'") {
        throw 'Could not identify the patched new-tab method name.'
    }
    $methodName = $Matches.name
    if (
        $methodText -notmatch
        "(?m)^\s+type\s+:\s+'\(Ljava/lang/String;\)V'"
    ) {
        throw (
            "The new-tab URL was injected into unexpected method " +
            "$classDescriptor->$methodName."
        )
    }
    if (
        $methodText -notmatch
        '(?m)^\s+access\s+:\s+0x[0-9a-f]+\s+\([^)]*PUBLIC[^)]*STATIC[^)]*\)'
    ) {
        throw "The patched new-tab method is not public static."
    }
    if ($methodText -notmatch '(?m)^\s+registers\s+:\s+(?<count>\d+)') {
        throw 'Could not identify the patched new-tab method register count.'
    }

    $registerCount = [int]$Matches.count
    $parameterRegister = $registerCount - 1
    $instructionLines = @(
        $methodLines | Where-Object { $_ -match '\|\d{4}:' }
    )
    foreach ($instruction in $instructionLines) {
        foreach ($register in [regex]::Matches($instruction, '\bv(?<index>\d+)\b')) {
            $registerIndex = [int]$register.Groups['index'].Value
            if ($registerIndex -ge $registerCount) {
                throw (
                    "Invalid register v$registerIndex in " +
                    "$classDescriptor->$methodName; method has " +
                    "$registerCount registers."
                )
            }
        }
    }

    $escapedExpectedInstruction = [regex]::Escape(
        "|0000: const-string v$parameterRegister, `"$ExpectedNewTabUrl`""
    )
    if ($methodText -notmatch $escapedExpectedInstruction) {
        throw (
            "The new-tab URL is not assigned to the String parameter " +
            "register v$parameterRegister at method entry."
        )
    }
    if ($methodText -notmatch '"chrome-native://newtab/"') {
        throw 'The patched method is not Edge Chromium new-tab URL setter.'
    }
    if (
        $methodText -notmatch
        [regex]::Escape("sput-object v$parameterRegister,")
    ) {
        throw 'The patched URL does not reach Edge new-tab URL field.'
    }

    Write-Host (
        "Verified custom new-tab bytecode: " +
        "$classDescriptor->$methodName uses v$parameterRegister."
    )
}
finally {
    if (Test-Path -LiteralPath $temporaryFiles) {
        Remove-Item -LiteralPath $temporaryFiles -Recurse -Force
    }
}
