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

function Get-DexDump {
    param(
        [Parameter(Mandatory)]
        [string]$DexPath
    )

    $dumpPath = Join-Path (
        Split-Path -Parent $DexPath
    ) "$([IO.Path]::GetFileNameWithoutExtension($DexPath)).txt"
    if (-not (Test-Path -LiteralPath $dumpPath)) {
        & $dexdumpExecutable -d -n $DexPath 2>&1 |
            Set-Content -LiteralPath $dumpPath -Encoding utf8
        if ($LASTEXITCODE -ne 0) {
            throw (
                "dexdump failed for $DexPath with exit code " +
                "$LASTEXITCODE."
            )
        }
    }

    [IO.File]::ReadAllText($dumpPath)
}

function Get-ContainingMethod {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$Text,

        [Parameter(Mandatory)]
        [int]$MarkerIndex
    )

    $headerPattern = '^\s+#\d+\s+:\s+\(in (?<class>L[^;]+;)\)'
    $header = [regex]::new(
        $headerPattern,
        [Text.RegularExpressions.RegexOptions]::Multiline -bor
            [Text.RegularExpressions.RegexOptions]::RightToLeft
    ).Match($Text, $MarkerIndex)
    if (-not $header.Success) {
        throw 'Could not identify the method containing a patched marker.'
    }
    $classDescriptor = $header.Groups['class'].Value

    $boundary = [regex]::new(
        '^(?:\s+#\d+\s+:\s+\(in L[^;]+;\)|\s+source_file_idx\s+:)',
        [Text.RegularExpressions.RegexOptions]::Multiline
    ).Match($Text, $MarkerIndex + 1)
    $methodEnd = if ($boundary.Success) {
        $boundary.Index
    }
    else {
        $Text.Length
    }
    $methodText = $Text.Substring(
        $header.Index,
        $methodEnd - $header.Index
    )

    if ($methodText -notmatch "(?m)^\s+name\s+:\s+'(?<name>[^']+)'") {
        throw 'Could not identify a patched method name.'
    }
    $methodName = $Matches.name

    [pscustomobject]@{
        Class = $classDescriptor
        Name = $methodName
        Text = $methodText
    }
}

function Assert-ValidRegisters {
    param(
        [Parameter(Mandatory)]
        [pscustomobject]$Method
    )

    if ($Method.Text -notmatch '(?m)^\s+registers\s+:\s+(?<count>\d+)') {
        throw "Could not identify registers for $($Method.Class)->$($Method.Name)."
    }
    $registerCount = [int]$Matches.count

    $instructions = [regex]::Matches(
        $Method.Text,
        '(?m)^.*\|[0-9a-f]{4}:.*$'
    )
    foreach ($register in [regex]::Matches(
        ($instructions.Value -join "`n"),
        '\bv(?<index>\d+)\b'
    )) {
        $registerIndex = [int]$register.Groups['index'].Value
        if ($registerIndex -ge $registerCount) {
            throw (
                "Invalid register v$registerIndex in " +
                "$($Method.Class)->$($Method.Name); method has " +
                "$registerCount registers."
            )
        }
    }

    $registerCount
}

try {
    New-Item -ItemType Directory -Path $temporaryFiles | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    $dexFiles = @()
    $stableIconEntries = @{}
    $canaryIconEntries = @{}
    $archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match '^classes(?:\d+)?\.dex$') {
                $dexPath = Join-Path $temporaryFiles $entry.Name
                [IO.Compression.ZipFileExtensions]::ExtractToFile(
                    $entry,
                    $dexPath
                )
                $dexFiles += $dexPath
                continue
            }

            if (
                $entry.FullName -match
                '^res/(?<directory>mipmap-[^/]+)/edge_app_icon(?<canary>_canary)?(?<extension>\.[^/]+)?$'
            ) {
                $key = "$($Matches.directory)$($Matches.extension)"
                $destination = if ($Matches.canary) {
                    $canaryIconEntries
                }
                else {
                    $stableIconEntries
                }
                $iconPath = Join-Path (
                    $temporaryFiles
                ) "$($Matches.directory)-edge-app-icon$($Matches.canary)$($Matches.extension)"
                [IO.Compression.ZipFileExtensions]::ExtractToFile(
                    $entry,
                    $iconPath
                )
                $destination[$key] = $iconPath
            }
        }
    }
    finally {
        $archive.Dispose()
    }

    if ($dexFiles.Count -eq 0) {
        throw 'The APK does not contain DEX files.'
    }
    Write-Verbose "Extracted $($dexFiles.Count) DEX files."
    if ($canaryIconEntries.Count -lt 2) {
        throw 'Expected both adaptive and bitmap Canary icon previews.'
    }
    foreach ($key in $canaryIconEntries.Keys) {
        if (-not $stableIconEntries.ContainsKey($key)) {
            throw "Stable icon counterpart is missing for $key."
        }
        $canaryHash = (
            Get-FileHash -Algorithm SHA256 -LiteralPath $canaryIconEntries[$key]
        ).Hash
        $stableHash = (
            Get-FileHash -Algorithm SHA256 -LiteralPath $stableIconEntries[$key]
        ).Hash
        if ($canaryHash -ne $stableHash) {
            throw "Canary icon preview still differs from the stable icon: $key."
        }
    }
    Write-Verbose "Verified $($canaryIconEntries.Count) Canary icon resources."

    $hiddenNewTabPreferenceKeys = @(
        'news_feed_toggle'
        'news_feed_category'
        'region_and_language'
        'news_source_perf'
        'news_interest_perf'
        'news_feed_footer'
        'ntp_wallpaper_category'
        'show_wallpaper_toggle'
        'edit_wallpaper_pref'
        'ntp_daily_image_pref'
        'content_service_category'
        'weather_widget_toggle'
        'temperature_pref'
        'weather_gps_detection_toggle'
        'ntp_on_startup_category'
        'browsing_options_pref'
    )
    $newTabSettingsDexCandidates = @(
        $dexFiles | Where-Object {
            $text = [Text.Encoding]::UTF8.GetString(
                [IO.File]::ReadAllBytes($_)
            )
            $text.Contains(
                'Lorg/chromium/chrome/browser/edge_settings/edge_ntp/EdgeNTPSettings;'
            ) -and
                $text.Contains('ntp_home_page_category') -and
                $text.Contains('home_page_pref')
        }
    )
    if ($newTabSettingsDexCandidates.Count -eq 0) {
        throw (
            'No DEX contains the EdgeNTPSettings verification markers.'
        )
    }
    $chromeWebStoreDexCandidates = @(
        $dexFiles | Where-Object {
            $text = [Text.Encoding]::UTF8.GetString(
                [IO.File]::ReadAllBytes($_)
            )
            $text.Contains(
                'Lapp/revanced/extension/edge/extensions/ChromeWebStore;'
            ) -and $text.Contains('onUrlUpdated')
        }
    )
    if ($chromeWebStoreDexCandidates.Count -eq 0) {
        throw 'No DEX contains the Chrome Web Store hook markers.'
    }

    $newTabUrlDexFiles = @(
        $dexFiles | Where-Object {
            [Text.Encoding]::UTF8.GetString(
                [IO.File]::ReadAllBytes($_)
            ).Contains($ExpectedNewTabUrl)
        }
    )
    if ($newTabUrlDexFiles.Count -ne 1) {
        throw (
            "Expected exactly one DEX containing the new-tab default URL, " +
            "found $($newTabUrlDexFiles.Count)."
        )
    }

    $chromiumNewTabDexFiles = @(
        $dexFiles | Where-Object {
            [Text.Encoding]::UTF8.GetString(
                [IO.File]::ReadAllBytes($_)
            ).Contains('chrome-native://newtab/')
        }
    )
    if ($chromiumNewTabDexFiles.Count -eq 0) {
        throw 'No DEX contains the Chromium new-tab URL marker.'
    }
    Write-Verbose (
        "Preparing disassembly for " +
        "$(@($newTabUrlDexFiles + $chromiumNewTabDexFiles | Select-Object -Unique).Count) DEX files."
    )

    $homepageText = Get-DexDump -DexPath $newTabUrlDexFiles[0]
    Write-Verbose 'Loaded homepage preference DEX.'
    $newTabSettingsDefinitions = @()
    foreach ($dexPath in $newTabSettingsDexCandidates) {
        $settingsText = Get-DexDump -DexPath $dexPath
        foreach ($marker in [regex]::Matches(
            $settingsText,
            [regex]::Escape(
                'EdgeNTPSettings.onViewCreated:' +
                    '(Landroid/view/View;Landroid/os/Bundle;)V'
            )
        )) {
            $newTabSettingsDefinitions += [pscustomobject]@{
                Text = $settingsText
                Marker = $marker
            }
        }
    }
    if ($newTabSettingsDefinitions.Count -ne 1) {
        throw (
            'Expected one EdgeNTPSettings.onViewCreated definition, found ' +
            "$($newTabSettingsDefinitions.Count)."
        )
    }
    $newTabSettingsDefinition = $newTabSettingsDefinitions[0]
    $newTabInitializerDefinitions = @()
    foreach ($marker in [regex]::Matches(
        $newTabSettingsDefinition.Text,
        [regex]::Escape('"ntp_home_page_category"')
    )) {
        $candidate = Get-ContainingMethod `
            -Text $newTabSettingsDefinition.Text `
            -MarkerIndex $marker.Index
        if (
            $candidate.Class -eq
                'Lorg/chromium/chrome/browser/edge_settings/edge_ntp/EdgeNTPSettings;' -and
            $candidate.Text -match
                "(?m)^\s+type\s+:\s+'\(Landroid/os/Bundle;Ljava/lang/String;\)V'" -and
            $candidate.Text -match [regex]::Escape('"home_page_pref"')
        ) {
            $newTabInitializerDefinitions += $candidate
        }
    }
    $newTabInitializerDefinitions = @(
        $newTabInitializerDefinitions |
            Sort-Object -Property Class, Name -Unique
    )
    if ($newTabInitializerDefinitions.Count -ne 1) {
        throw (
            'Expected one EdgeNTPSettings preference initializer, found ' +
            "$($newTabInitializerDefinitions.Count)."
        )
    }
    $expectedHiddenNewTabPreferenceKeys = @(
        $hiddenNewTabPreferenceKeys | Where-Object {
            $newTabInitializerDefinitions[0].Text -match
                [regex]::Escape("`"$_`"")
        }
    )
    $newTabSettings = Get-ContainingMethod `
        -Text $newTabSettingsDefinition.Text `
        -MarkerIndex $newTabSettingsDefinition.Marker.Index
    $newTabSettingsRegisters = Assert-ValidRegisters -Method $newTabSettings
    if ($newTabSettingsRegisters -ne 3) {
        throw 'EdgeNTPSettings.onViewCreated no longer has three parameter registers.'
    }
    $newTabSettingsInstructions = @(
        [regex]::Matches(
            $newTabSettings.Text,
            '(?m)^.*\|[0-9a-f]{4}:.*$'
        ).Value
    )
    if (
        $newTabSettingsInstructions -match
        '\|[0-9a-f]{4}:\s+(?:if-|goto)'
    ) {
        throw 'The injected new-tab settings flow must remain branch-free.'
    }
    foreach ($preferenceKey in $expectedHiddenNewTabPreferenceKeys) {
        $keyInstructionIndexes = @(
            0..($newTabSettingsInstructions.Count - 1) | Where-Object {
                $newTabSettingsInstructions[$_] -match
                [regex]::Escape("`"$preferenceKey`"")
            }
        )
        if ($keyInstructionIndexes.Count -ne 1) {
            throw (
                "Expected one injected lookup for $preferenceKey, found " +
                "$($keyInstructionIndexes.Count)."
            )
        }
        $keyInstructionIndex = $keyInstructionIndexes[0]
        if ($keyInstructionIndex + 3 -ge $newTabSettingsInstructions.Count) {
            throw "The injected visibility flow is incomplete for $preferenceKey."
        }
        if (
            $newTabSettingsInstructions[$keyInstructionIndex + 1] -notmatch
            'invoke-virtual\s+\{v0,\s+v1\},.*' +
            '\(Ljava/lang/CharSequence;\)Landroidx/preference/Preference;'
        ) {
            throw "The injected preference lookup is invalid for $preferenceKey."
        }
        if (
            $newTabSettingsInstructions[$keyInstructionIndex + 2] -notmatch
            'move-result-object\s+v1'
        ) {
            throw "The injected preference result is invalid for $preferenceKey."
        }
        if (
            $newTabSettingsInstructions[$keyInstructionIndex + 3] -notmatch
            'invoke-virtual\s+\{v1,\s+v2\},\s+' +
            'Landroidx/preference/Preference;\.setVisible:\(Z\)V'
        ) {
            throw "The injected visibility call is invalid for $preferenceKey."
        }
    }
    Write-Verbose 'Verified branch-free Edge new-tab settings bytecode.'

    $chromeWebStoreHookDefinitions = @()
    foreach ($dexPath in $chromeWebStoreDexCandidates) {
        $dexText = Get-DexDump -DexPath $dexPath
        foreach ($marker in [regex]::Matches(
            $dexText,
            [regex]::Escape(
                'Lapp/revanced/extension/edge/extensions/ChromeWebStore;' +
                    '.onUrlUpdated:(Ljava/lang/Object;Ljava/lang/String;)V'
            )
        )) {
            $candidate = Get-ContainingMethod `
                -Text $dexText `
                -MarkerIndex $marker.Index
            if (
                $candidate.Class -ne
                    'Lapp/revanced/extension/edge/extensions/ChromeWebStore;' -and
                $candidate.Text -match [regex]::Escape(
                    '(Lorg/chromium/chrome/browser/tab/Tab;' +
                        'Lorg/chromium/url/GURL;)V'
                )
            ) {
                $chromeWebStoreHookDefinitions += $candidate
            }
        }
    }
    $chromeWebStoreHookDefinitions = @(
        $chromeWebStoreHookDefinitions |
            Sort-Object -Property Class, Name -Unique
    )
    if ($chromeWebStoreHookDefinitions.Count -ne 1) {
        throw (
            'Expected one Chrome Web Store URL hook, found ' +
            "$($chromeWebStoreHookDefinitions.Count)."
        )
    }
    $chromeWebStoreHook = $chromeWebStoreHookDefinitions[0]
    [void](Assert-ValidRegisters -Method $chromeWebStoreHook)
    $chromeWebStoreInstructions = @(
        [regex]::Matches(
            $chromeWebStoreHook.Text,
            '(?m)^.*\|[0-9a-f]{4}:.*$'
        ).Value
    )
    if ($chromeWebStoreInstructions.Count -lt 5) {
        throw 'The Chrome Web Store URL hook is incomplete.'
    }
    if (
        $chromeWebStoreInstructions[0] -notmatch
            '\|[0-9a-f]{4}:\s+if-eqz\s+(?<urlRegister>v\d+),\s+' +
            '(?<originalAddress>[0-9a-f]{4})'
    ) {
        throw 'The Chrome Web Store hook does not guard a missing URL.'
    }
    $urlRegister = $Matches.urlRegister
    $originalAddress = $Matches.originalAddress
    if (
        $chromeWebStoreInstructions[1] -notmatch
            "invoke-virtual\s+\{$urlRegister\},\s+" +
            'Lorg/chromium/url/GURL;\.j:\(\)Ljava/lang/String;'
    ) {
        throw 'The Chrome Web Store hook does not convert the updated URL.'
    }
    if (
        $chromeWebStoreInstructions[2] -notmatch
            'move-result-object\s+(?<temporaryRegister>v\d+)'
    ) {
        throw 'The Chrome Web Store hook URL result is invalid.'
    }
    $temporaryRegister = $Matches.temporaryRegister
    if (
        $chromeWebStoreInstructions[3] -notmatch
            "invoke-static\s+\{v\d+,\s+$temporaryRegister\},\s+" +
            'Lapp/revanced/extension/edge/extensions/ChromeWebStore;' +
            '\.onUrlUpdated:\(Ljava/lang/Object;Ljava/lang/String;\)V'
    ) {
        throw 'The Chrome Web Store callback arguments are invalid.'
    }
    if (
        $chromeWebStoreInstructions[4] -notmatch
            "\|${originalAddress}:"
    ) {
        throw 'The Chrome Web Store guard does not resume the original method.'
    }
    Write-Verbose 'Verified Chrome Web Store URL hook control flow.'

    $escapedUrl = [regex]::Escape("`"$ExpectedNewTabUrl`"")
    $urlMarkers = @(
        [regex]::Matches(
            $homepageText,
            "\|[0-9a-f]{4}:\s+const-string(?:/jumbo)?\s+(?<register>v\d+),\s+$escapedUrl"
        )
    )
    if ($urlMarkers.Count -ne 1) {
        throw (
            "Expected one executable new-tab default URL marker, found " +
            "$($urlMarkers.Count)."
        )
    }

    $urlMarker = $urlMarkers[0]
    $urlRegister = $urlMarker.Groups['register'].Value
    $homepageReader = Get-ContainingMethod `
        -Text $homepageText `
        -MarkerIndex $urlMarker.Index
    Write-Verbose 'Identified homepage URL reader.'
    $homepageRegisters = Assert-ValidRegisters -Method $homepageReader
    if (
        $homepageReader.Text -notmatch
        "(?m)^\s+type\s+:\s+'\(\)Lorg/chromium/url/GURL;'"
    ) {
        throw 'The default URL was not injected into the homepage GURL reader.'
    }
    foreach ($preferenceKey in @(
        'Chrome.Homepage.CustomGurl',
        'homepage_custom_uri'
    )) {
        if ($homepageReader.Text -notmatch [regex]::Escape(
            "`"$preferenceKey`""
        )) {
            throw "Homepage reader does not use $preferenceKey."
        }
    }
    $homepageInstructions = @(
        [regex]::Matches(
            $homepageReader.Text,
            '(?m)^.*\|[0-9a-f]{4}:.*$'
        ).Value
    )
    $urlInstructionIndexes = @(
        0..($homepageInstructions.Count - 1) | Where-Object {
            $homepageInstructions[$_] -match $escapedUrl
        }
    )
    if ($urlInstructionIndexes.Count -ne 1) {
        throw 'Could not identify the default homepage URL flow.'
    }
    $urlInstructionIndex = $urlInstructionIndexes[0]
    if ($urlInstructionIndex + 3 -ge $homepageInstructions.Count) {
        throw 'The default homepage URL flow is incomplete.'
    }
    if (
        $homepageInstructions[$urlInstructionIndex + 1] -notmatch
        "invoke-static\s+\{$urlRegister\},\s+" +
        'Lorg/chromium/url/GURL;\.[^:]+:' +
        '\(Ljava/lang/String;\)Lorg/chromium/url/GURL;'
    ) {
        throw 'The default homepage URL is not converted to a GURL.'
    }
    if (
        $homepageInstructions[$urlInstructionIndex + 2] -notmatch
        "move-result-object\s+$urlRegister"
    ) {
        throw 'The default homepage GURL result is not retained.'
    }
    if (
        $homepageInstructions[$urlInstructionIndex + 3] -notmatch
        "return-object\s+$urlRegister"
    ) {
        throw 'The default homepage URL is not the reader fallback.'
    }

    $selectionMarkers = @(
        [regex]::Matches(
            $homepageText,
            '"homepage_partner_enabled"'
        )
    )
    if ($selectionMarkers.Count -ne 1) {
        throw (
            "Expected one homepage selection preference marker, found " +
            "$($selectionMarkers.Count)."
        )
    }
    $homepageMutation = Get-ContainingMethod `
        -Text $homepageText `
        -MarkerIndex $selectionMarkers[0].Index
    if (
        $homepageMutation.Text -notmatch
        [regex]::Escape('"Chrome.Homepage.CustomGurl"')
    ) {
        throw 'Homepage mutation method does not update the custom URL.'
    }

    $escapedHomepageClass = [regex]::Escape($homepageReader.Class)
    $selectionCallPattern =
        "invoke-virtual\s+\{v\d+\},\s+$escapedHomepageClass" +
        '\.(?<method>[^:]+):\(\)Z'
    $selectionCalls = @(
        [regex]::Matches(
            $homepageMutation.Text,
            $selectionCallPattern
        )
    )
    if ($selectionCalls.Count -ne 1) {
        throw 'Could not identify the homepage source selection method.'
    }
    $selectionMethodName = $selectionCalls[0].Groups['method'].Value
    $displayClass = ($homepageReader.Class).TrimStart('L').TrimEnd(';')
    $selectionDefinitionPattern = [regex]::Escape(
        "$displayClass.$selectionMethodName`:()Z"
    )
    $selectionDefinitions = @(
        [regex]::Matches(
            $homepageText,
            "(?m)\]\s+$selectionDefinitionPattern\r?$"
        )
    )
    if ($selectionDefinitions.Count -ne 1) {
        throw 'Could not identify the homepage source selection implementation.'
    }
    $homepageSelection = Get-ContainingMethod `
        -Text $homepageText `
        -MarkerIndex $selectionDefinitions[0].Index
    Write-Verbose 'Identified homepage source selection method.'
    $selectionInstructions = @(
        [regex]::Matches(
            $homepageSelection.Text,
            '(?m)^.*\|[0-9a-f]{4}:.*$'
        ).Value
    )
    if ($selectionInstructions.Count -ne 2) {
        throw 'Edge can still select the built-in Microsoft new-tab page.'
    }
    if (
        $selectionInstructions[0] -notmatch
        'const/4\s+(?<register>v\d+),\s+#int 0'
    ) {
        throw 'Edge can still select the built-in Microsoft new-tab page.'
    }
    $selectionRegister = $Matches.register
    if (
        $selectionInstructions[1] -notmatch
        "return\s+$([regex]::Escape($selectionRegister))"
    ) {
        throw 'Edge can still select the built-in Microsoft new-tab page.'
    }

    $newTabSetterCandidates = @()
    foreach ($dexPath in $chromiumNewTabDexFiles) {
        Write-Verbose "Inspecting Chromium new-tab candidate $dexPath."
        $setterText = Get-DexDump -DexPath $dexPath
        $setterMarkers = [regex]::Matches(
            $setterText,
            '"chrome-native://newtab/"'
        )
        foreach ($setterMarker in $setterMarkers) {
            $candidate = Get-ContainingMethod `
                -Text $setterText `
                -MarkerIndex $setterMarker.Index
            if (
                $candidate.Text -match
                "(?m)^\s+type\s+:\s+'\(Ljava/lang/String;\)V'" -and
                $candidate.Text -match
                '(?m)^\s+access\s+:\s+0x[0-9a-f]+\s+\([^)]*PUBLIC[^)]*STATIC[^)]*\)'
            ) {
                $newTabSetterCandidates += $candidate
            }
        }
    }
    if ($newTabSetterCandidates.Count -ne 1) {
        throw (
            "Expected one public static Chromium new-tab setter, found " +
            "$($newTabSetterCandidates.Count)."
        )
    }

    $newTabSetter = $newTabSetterCandidates[0]
    $setterRegisters = Assert-ValidRegisters -Method $newTabSetter
    $parameterRegister = $setterRegisters - 1
    $managerMethodPattern =
        'invoke-static\s+\{\},\s+' +
        $escapedHomepageClass +
        '\.(?<method>[^:]+):\(\)' +
        $escapedHomepageClass
    $managerCalls = @(
        [regex]::Matches($newTabSetter.Text, $managerMethodPattern)
    )
    if ($managerCalls.Count -ne 1) {
        throw 'New-tab setter does not obtain the homepage preference manager.'
    }
    $managerMethodName = $managerCalls[0].Groups['method'].Value
    $expectedSetterFlow = @(
        (
            "invoke-static {}, " +
                "$($homepageReader.Class).$managerMethodName`:()" +
                $homepageReader.Class
        )
        (
            "invoke-virtual {v0}, " +
                "$($homepageReader.Class).$($homepageReader.Name)" +
                ':()Lorg/chromium/url/GURL;'
        )
        'invoke-virtual {v0}, Lorg/chromium/url/GURL;.j:()Ljava/lang/String;'
        "move-result-object v$parameterRegister"
    )
    foreach ($instruction in $expectedSetterFlow) {
        if (
            $newTabSetter.Text -notmatch
            [regex]::Escape($instruction)
        ) {
            throw "New-tab setter is missing preference flow: $instruction"
        }
    }
    if (
        $newTabSetter.Text -notmatch
        [regex]::Escape("sput-object v$parameterRegister,")
    ) {
        throw 'The saved custom URL does not reach Edge new-tab URL field.'
    }

    $tabLayoutHookCandidates = @()
    foreach ($dexPath in $dexFiles) {
        $rawText = [Text.Encoding]::UTF8.GetString(
            [IO.File]::ReadAllBytes($dexPath)
        )
        if (
            -not $rawText.Contains(
                'Lapp/revanced/extension/edge/tabs/TabSwitcherMobile;'
            ) -or
            -not $rawText.Contains('updateLayout')
        ) {
            continue
        }

        $tabText = Get-DexDump -DexPath $dexPath
        foreach ($marker in [regex]::Matches(
            $tabText,
            'invoke-static\s+\{[^}]+\},\s+' +
                'Lapp/revanced/extension/edge/tabs/TabSwitcherMobile;' +
                '\.updateLayout:\(Ljava/lang/Object;I\)V'
        )) {
            $candidate = Get-ContainingMethod `
                -Text $tabText `
                -MarkerIndex $marker.Index
            if (
                -not $candidate.Class.StartsWith(
                    'Lapp/revanced/extension/'
                )
            ) {
                $tabLayoutHookCandidates += $candidate
            }
        }
    }
    if ($tabLayoutHookCandidates.Count -ne 1) {
        throw (
            'Expected one tab-layout hook call, found ' +
            "$($tabLayoutHookCandidates.Count)."
        )
    }

    $tabLayoutHook = $tabLayoutHookCandidates[0]
    Assert-ValidRegisters -Method $tabLayoutHook | Out-Null
    $tabLayoutInstructions = @(
        [regex]::Matches(
            $tabLayoutHook.Text,
            '(?m)^.*\|[0-9a-f]{4}:.*$'
        ).Value
    )
    if ($tabLayoutInstructions.Count -lt 7) {
        throw 'The tab-layout callback is incomplete.'
    }
    if (
        $tabLayoutInstructions[0] -notmatch
        'iget-object\s+(?<view>v\d+),\s+v\d+,\s+' +
            'L[^;]+;\.[^:]+:' +
            'Lorg/chromium/chrome/browser/tasks/tab_management/' +
            'TabListRecyclerView;'
    ) {
        throw 'The tab-layout hook does not load the tab list first.'
    }
    $tabListRegister = $Matches.view
    if (
        $tabLayoutInstructions[1] -notmatch
        "invoke-virtual\s+\{$tabListRegister\},\s+" +
            'Landroidx/recyclerview/widget/RecyclerView;\.[^:]+:' +
            '\(\)(?<adapter>L[^;]+;)'
    ) {
        throw 'The tab-layout hook does not read the RecyclerView adapter.'
    }
    $adapterType = $Matches.adapter
    if (
        $tabLayoutInstructions[2] -notmatch
        'move-result-object\s+(?<adapterRegister>v\d+)'
    ) {
        throw 'The tab-layout hook does not retain the RecyclerView adapter.'
    }
    $adapterRegister = $Matches.adapterRegister
    if (
        $tabLayoutInstructions[3] -notmatch
        "invoke-virtual\s+\{$adapterRegister\},\s+" +
            "$([regex]::Escape($adapterType))\.getItemCount:\(\)I"
    ) {
        throw 'The tab-layout hook does not read the adapter item count.'
    }
    if (
        $tabLayoutInstructions[4] -notmatch
        'move-result\s+(?<countRegister>v\d+)'
    ) {
        throw 'The tab-layout hook does not retain the adapter item count.'
    }
    $countRegister = $Matches.countRegister
    if (
        $tabLayoutInstructions[5] -notmatch
        "invoke-static\s+\{$tabListRegister,\s+$countRegister\},\s+" +
            'Lapp/revanced/extension/edge/tabs/TabSwitcherMobile;' +
            '\.updateLayout:\(Ljava/lang/Object;I\)V'
    ) {
        throw 'The tab-layout hook arguments are invalid.'
    }
    if (
        $tabLayoutInstructions[6] -match
        '\|[0-9a-f]{4}:\s+(?:return|goto)'
    ) {
        throw 'The tab-layout hook skips the original Edge callback.'
    }
    if (
        (
            $tabLayoutInstructions[6..($tabLayoutInstructions.Count - 1)] `
                -join "`n"
        ) -notmatch
        'invoke-virtual\s+\{v\d+\},\s+L[^;]+;\.run:\(\)V'
    ) {
        throw 'The original Edge animation continuation is no longer reachable.'
    }

    Write-Host (
        "Verified preference-backed new tab: " +
        "$($homepageReader.Class)->$($homepageReader.Name), " +
        "$($newTabSetter.Class)->$($newTabSetter.Name)."
    )
    Write-Host (
        "Verified branch-free new-tab settings for " +
        "$($expectedHiddenNewTabPreferenceKeys.Count) available hidden preferences."
    )
    Write-Host (
        "Verified Chrome Web Store URL hook in " +
        "$($chromeWebStoreHook.Class)->$($chromeWebStoreHook.Name)."
    )
    Write-Host (
        "Verified stable Edge icon in " +
        "$($canaryIconEntries.Count) Canary preview resources."
    )
    Write-Host (
        "Verified branch-free tab-layout hook in " +
        "$($tabLayoutHook.Class)->$($tabLayoutHook.Name)."
    )
}
finally {
    if (Test-Path -LiteralPath $temporaryFiles) {
        Remove-Item -LiteralPath $temporaryFiles -Recurse -Force
    }
}
