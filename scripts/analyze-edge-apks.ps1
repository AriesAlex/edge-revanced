param(
    [Parameter(Mandatory = $true)]
    [string[]] $Apk,

    [Parameter(Mandatory = $true)]
    [string] $Output
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$compressionMethodProperty = [System.IO.Compression.ZipArchiveEntry].GetProperty(
    'CompressionMethod',
    [System.Reflection.BindingFlags]'Instance,NonPublic'
)
if ($null -eq $compressionMethodProperty) {
    throw 'The current .NET runtime does not expose ZIP compression metadata.'
}

function Get-EntryCategory {
    param([string] $Name)

    if ($Name -eq 'AndroidManifest.xml') { return 'manifest' }
    if ($Name -eq 'resources.arsc') { return 'resource-table' }
    if ($Name -match '^classes\d*\.dex$') { return 'dex' }
    if ($Name -match '^lib/[^/]+/[^/]+\.so$') { return 'native' }
    if ($Name -match '^assets/(?:resources\.pak|icudtl\.dat|.*snapshot.*\.bin)$') {
        return 'chromium-runtime-asset'
    }
    if ($Name -match '^assets/') { return 'asset' }
    if ($Name -match '^res/') { return 'android-resource' }
    if ($Name -match '^META-INF/') { return 'signature' }
    return 'other'
}

function Get-EntryHash {
    param([System.IO.Compression.ZipArchiveEntry] $Entry)

    $stream = $Entry.Open()
    try {
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        try {
            return [Convert]::ToHexString(
                $sha256.ComputeHash($stream)
            ).ToLowerInvariant()
        }
        finally {
            $sha256.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }
}

function Get-CompressionMethod {
    param([System.IO.Compression.ZipArchiveEntry] $Entry)

    return $compressionMethodProperty.GetValue($Entry).ToString()
}

function Get-ApkInventory {
    param([string] $Path)

    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    $archive = [System.IO.Compression.ZipFile]::OpenRead($resolvedPath)
    try {
        $entries = [ordered]@{}
        foreach ($entry in $archive.Entries) {
            if ([string]::IsNullOrEmpty($entry.Name)) { continue }

            $entries[$entry.FullName] = [ordered]@{
                category = Get-EntryCategory $entry.FullName
                size = $entry.Length
                compressedSize = $entry.CompressedLength
                compressionMethod = Get-CompressionMethod $entry
                sha256 = Get-EntryHash $entry
            }
        }

        return [ordered]@{
            path = $resolvedPath
            size = (Get-Item -LiteralPath $resolvedPath).Length
            sha256 = (
                Get-FileHash -LiteralPath $resolvedPath -Algorithm SHA256
            ).Hash.ToLowerInvariant()
            entries = $entries
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Get-CategoryCounts {
    param([object[]] $Changes)

    $counts = [ordered]@{}
    foreach ($change in $Changes) {
        $category = $change.category
        if (-not $counts.Contains($category)) { $counts[$category] = 0 }
        $counts[$category]++
    }
    return $counts
}

function Compare-ApkInventory {
    param(
        [System.Collections.IDictionary] $Old,
        [System.Collections.IDictionary] $New
    )

    $names = [System.Collections.Generic.SortedSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($name in $Old.entries.Keys) { [void] $names.Add($name) }
    foreach ($name in $New.entries.Keys) { [void] $names.Add($name) }

    $added = [System.Collections.Generic.List[object]]::new()
    $removed = [System.Collections.Generic.List[object]]::new()
    $changed = [System.Collections.Generic.List[object]]::new()
    $packagingChanged = [System.Collections.Generic.List[object]]::new()
    $same = 0

    foreach ($name in $names) {
        $oldEntry = $Old.entries[$name]
        $newEntry = $New.entries[$name]
        if ($null -eq $oldEntry) {
            $added.Add([ordered]@{
                name = $name
                category = $newEntry.category
            })
        }
        elseif ($null -eq $newEntry) {
            $removed.Add([ordered]@{
                name = $name
                category = $oldEntry.category
            })
        }
        elseif ($oldEntry.sha256 -ne $newEntry.sha256) {
            $changed.Add([ordered]@{
                name = $name
                category = $newEntry.category
                oldSize = $oldEntry.size
                newSize = $newEntry.size
                oldSha256 = $oldEntry.sha256
                newSha256 = $newEntry.sha256
            })
        }
        elseif (
            $oldEntry.compressionMethod -ne $newEntry.compressionMethod
        ) {
            $packagingChanged.Add([ordered]@{
                name = $name
                category = $newEntry.category
                oldCompressionMethod = $oldEntry.compressionMethod
                newCompressionMethod = $newEntry.compressionMethod
            })
        }
        else {
            $same++
        }
    }

    return [ordered]@{
        oldPath = $Old.path
        newPath = $New.path
        same = $same
        added = $added
        removed = $removed
        changed = $changed
        packagingChanged = $packagingChanged
        addedByCategory = Get-CategoryCounts $added
        removedByCategory = Get-CategoryCounts $removed
        changedByCategory = Get-CategoryCounts $changed
        packagingChangedByCategory = Get-CategoryCounts $packagingChanged
    }
}

if ($Apk.Count -lt 2) {
    throw 'Provide at least two APKs in chronological order.'
}

$inventories = [System.Collections.Generic.List[object]]::new()
foreach ($path in $Apk) {
    Write-Host "Hashing $path"
    $inventories.Add((Get-ApkInventory $path))
}

$comparisons = [System.Collections.Generic.List[object]]::new()
for ($index = 1; $index -lt $inventories.Count; $index++) {
    $comparisons.Add(
        (Compare-ApkInventory $inventories[$index - 1] $inventories[$index])
    )
}

$result = [ordered]@{
    generatedAt = [DateTimeOffset]::UtcNow.ToString('O')
    inventories = $inventories
    comparisons = $comparisons
}

$outputPath = [System.IO.Path]::GetFullPath($Output)
$outputDirectory = [System.IO.Path]::GetDirectoryName($outputPath)
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$result |
    ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $outputPath -Encoding utf8
Write-Host "Wrote $outputPath"
