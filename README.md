<p align="center">
  <strong>English</strong> · <a href="README.ru.md">Русский</a>
</p>

<p align="center">
  <img src="docs/assets/edge-revanced-miku.png" width="240" alt="Edge ReVanced — Miku Edition">
</p>

<h1 align="center">Edge ReVanced</h1>

<p align="center">
  <strong>Microsoft Edge Canary for Android, brought closer to the Kiwi Browser experience.</strong>
  <br>
  Chrome Web Store extensions, on-device DevTools, a custom new tab page,
  and a thumb-friendly tab switcher.
</p>

<p align="center">
  <a href="https://github.com/AriesAlex/edge-revanced/releases/latest/download/edge-revanced.apk">
    <img alt="Download Edge ReVanced APK" src="https://img.shields.io/badge/DOWNLOAD-Edge_ReVanced_APK-0A84FF?style=for-the-badge&amp;logo=microsoftedge&amp;logoColor=white">
  </a>
  <a href="https://github.com/AriesAlex/edge-revanced/releases">
    <img alt="Open Releases" src="https://img.shields.io/badge/RELEASES-build_history-24292F?style=for-the-badge&amp;logo=github&amp;logoColor=white">
  </a>
</p>

<p align="center">
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white">
  <img alt="ARM64" src="https://img.shields.io/badge/ABI-arm64--v8a-5965E0?style=flat-square">
  <img alt="ReVanced Patcher 22" src="https://img.shields.io/badge/ReVanced_Patcher-22-E11D48?style=flat-square">
  <a href="https://github.com/AriesAlex/edge-revanced/actions/workflows/release.yml">
    <img alt="Build Edge ReVanced APK" src="https://github.com/AriesAlex/edge-revanced/actions/workflows/release.yml/badge.svg">
  </a>
  <a href="LICENSE">
    <img alt="GPLv3" src="https://img.shields.io/badge/license-GPLv3-blue?style=flat-square">
  </a>
</p>

> [!IMPORTANT]
> The prebuilt APK targets Android 10+ and `arm64-v8a`.
> It cannot update the official Edge Canary because it uses a different signing
> key. Uninstall the official Canary once before installing Edge ReVanced.
> Future Edge ReVanced releases update in place without losing browser data.

## What changes

- **Extensions without a whitelist.** The familiar blue install button works
  directly on Chrome Web Store pages. A successfully installed extension is
  enabled immediately; there is no need to copy its ID into Developer options.
  Individual extension UIs may still depend on mobile support from their author.
- **DevTools on the phone.** Edge gains a **DevTools** menu item. The bundled
  Chromium frontend connects to the current tab through a local CDP proxy, so
  neither a computer nor remote debugging is required.
- **Your new tab, not Microsoft’s feed.** The new-tab settings contain one URL
  editor; MSN, news, weather, and wallpaper controls are removed. New profiles
  default to [`tabpage.ariex.ru`](https://tabpage.ariex.ru), which supports
  separate settings for multiple accounts, but the URL can be changed inside
  Edge at any time.
- **Tabs within right-thumb reach.** The first card starts at the lower right,
  newer tabs grow upward in reverse order, and a short grid sits in a reachable
  area. Long lists still scroll all the way to the screen edge without a
  permanent empty gap below.
- **Swipe up for tabs.** Swiping upward on the toolbar opens the tab switcher
  with either top or bottom address-bar placement.
- **Custom branding.** The app is named `Edge ReVanced`, uses the regular Edge
  icon instead of the Canary badge, and shows Miku artwork in the real Android
  system splash without an artificial launch delay.
- **No recurring account notice.** Only the repeated Microsoft account summary
  dialog is dismissed; account sign-in and sync continue to work.

## Screenshots

<table>
  <tr>
    <td width="50%" align="center" valign="top">
      <h3>Custom new tab</h3>
      <p>
        A full personal start page replaces Microsoft NTP, with sync,
        multiple accounts, and large touch targets.
      </p>
      <img src="docs/assets/new-tab.webp" width="280" alt="Custom new tab in Edge ReVanced">
    </td>
    <td width="50%" align="center" valign="top">
      <h3>Mobile DevTools</h3>
      <p>
        DOM, styles, Console, Sources, Network, and the rest of Chromium
        DevTools. The <code>»</code> overflow button remains reachable.
      </p>
      <img src="docs/assets/devtools.webp" width="280" alt="Chromium DevTools on a phone in Edge ReVanced">
    </td>
  </tr>
  <tr>
    <td width="50%" align="center" valign="top">
      <h3>Thumb-friendly tabs</h3>
      <p>
        The active tab is on the right, older cards sit below, and newer tabs
        grow upward. Primary actions no longer require crossing the screen.
      </p>
      <img src="docs/assets/tabs.webp" width="280" alt="Right-thumb tab switcher in Edge ReVanced">
    </td>
    <td width="50%" align="center" valign="top">
      <h3>Real Android splash</h3>
      <p>
        The system splash is replaced as an Android resource: no second
        Activity, fake screen, or launch delay.
      </p>
      <img src="docs/assets/splash.webp" width="280" alt="Edge ReVanced system splash with Miku artwork">
    </td>
  </tr>
</table>

<p align="center">
  <sub>
    Real Edge Canary 152.0.4184.0 screenshots from a OnePlus 13 running
    Android 16 in portrait orientation.
  </sub>
</p>

## Install

1. Download [`edge-revanced.apk`](https://github.com/AriesAlex/edge-revanced/releases/latest/download/edge-revanced.apk).
2. If the official **Edge Canary** is installed, uninstall it once because the
   signatures differ.
3. Open the APK and allow installation from the selected source.
4. Install future releases over Edge ReVanced to keep the profile, tabs, and
   settings.

The same APK can be installed through ADB:

```powershell
adb install -r 'C:\path\to\edge-revanced.apk'
```

The stable link above always downloads the latest raw APK, not a ZIP archive.
The versioned APK and matching `.rvp` are available on the
[Releases](https://github.com/AriesAlex/edge-revanced/releases) page.

### Change the new tab URL

Open **Settings → New tab page → New tab URL**, enter an absolute HTTP or HTTPS
address, and confirm it. The next new tab uses the saved value immediately;
rebuilding or repatching the APK is not required.

English is the fallback application locale, and the added UI is also localized
in Russian. Edge ReVanced does not force the device or browser language.

## Project architecture

```text
clean monolithic Edge Canary APK (arm64-v8a)
                         │
                         ▼
                ReVanced Patcher 22
                         │
       ┌─────────────────┼──────────────────┐
       │                 │                  │
 Kotlin bytecode     mobile.rve        DevTools frontend
 resource patches    runtime Java      + touch adaptation
       │                 │                  │
       └─────────────────┼──────────────────┘
                         ▼
              rebuilt DEX and resources
                         │
                         ▼
             persistent private signing key
                         │
                         ▼
                    patched APK
```

- [`EdgePatches.kt`](patches/src/main/kotlin/app/revanced/patches/edge/EdgePatches.kt)
  contains structural fingerprints and static DEX/resource changes.
- [`extensions/edge/mobile`](extensions/edge/mobile) builds into `mobile.rve`.
  Runtime code hosts the DevTools proxy, handles Chrome Web Store installation,
  dismisses the exact account notice, and adjusts the Android tab-switcher
  views.
- [`devtools-mobile.js`](scripts/devtools-mobile.js) adapts the bundled Chromium
  DevTools frontend to a narrow touch interface.
- [`bootstrap.ps1`](scripts/bootstrap.ps1) verifies ReVanced CLI by SHA-256,
  checks out a pinned official Gradle plugin commit, and prepares DevTools.
- [`patch.ps1`](scripts/patch.ps1) applies the `.rvp`, repackages, and signs the
  APK. [`verify-patched-apk.ps1`](scripts/verify-patched-apk.ps1) checks the
  preference-backed new-tab bytecode and Canary icon replacement.
- [`edge-canary.ts`](scripts/edge-canary.ts) discovers and downloads the latest
  monolithic ARM64 Canary APK for CI through the public contract of the
  MIT-licensed [EFF apkeep](https://github.com/EFForg/apkeep) project.

An `.rvp` is a JAR container with patch metadata, JVM patch classes, their
Android DEX form, the runtime extension, and resources. Microsoft Edge code is
not distributed inside the patch bundle.

<details>
<summary><strong>Build from source</strong></summary>

### Requirements

- Windows PowerShell;
- Git;
- JDK 21;
- Bun;
- Android SDK Platform `37.0` and Build-Tools `37.0.0`;
- a clean monolithic `arm64-v8a` Edge Canary APK, not split APKs;
- your own ReVanced CLI keystore for local signing.

### Prepare and build the patch bundle

```powershell
.\scripts\bootstrap.ps1
.\scripts\build.ps1
```

Bootstrap creates reproducible ignored artifacts:

- ReVanced CLI `6.0.0` / Patcher `22.0.0`;
- the official `revanced-patches-gradle-plugin` at a pinned commit;
- Chromium DevTools frontend with English and Russian locales.

The frontend is packed into a deterministic ZIP with a source manifest and
SHA-256 hashes. A repeated bootstrap verifies the complete archive and becomes
a no-op.

### Create an APK

Place an ignored `edge-mod.keystore` in the repository root, or pass its path
explicitly:

```powershell
.\scripts\patch.ps1 `
    -Apk 'C:\path\to\Edge-Canary-arm64.apk' `
    -Keystore 'C:\secure\edge-mod.keystore'
```

Without `-Rvp`, the script first builds the bundle from current sources. An
already built bundle can be applied without Gradle, Bun, or another DevTools
download:

```powershell
.\scripts\patch.ps1 `
    -Apk 'C:\path\to\Edge-Canary-arm64.apk' `
    -Rvp 'C:\path\to\edge-revanced.rvp' `
    -Keystore 'C:\secure\edge-mod.keystore'
```

The DevTools frontend is already stored inside the `.rvp`, but repackaging still
requires ReVanced CLI, Android SDK framework 37, a compatible `aapt2`, the source
APK, and a persistent signing key.

`-NewTabUrl` changes the initial URL for a fresh profile:

```powershell
.\scripts\patch.ps1 `
    -Apk 'C:\path\to\Edge-Canary-arm64.apk' `
    -NewTabUrl 'https://example.com/start'
```

Users can later change that address in Edge settings without another build.

For a separate test installation, use package
`com.microsoft.emmx.canary.revanced`:

```powershell
.\scripts\patch.ps1 `
    -Apk 'C:\path\to\Edge-Canary-arm64.apk' `
    -SideBySide
```

Side-by-side mode is not the primary distribution mode: Microsoft/Google login
and external integrations may validate the original package name.

Do not rotate your signing key between builds. Android only updates an installed
app with an APK signed by the same identity. The official Edge ReVanced key is
not stored in Git; GitHub Actions reconstructs it from the encrypted repository
secret `EDGE_MOD_KEYSTORE_BASE64`.

</details>

<details>
<summary><strong>When a new Edge version is released</strong></summary>

The patches are not tied to an allowlist of Edge versions. Injection points are
found through structural evidence: stable Chromium/Microsoft types, signatures,
strings, resource references, and characteristic opcode sequences.

1. Download the new monolithic ARM64 Canary APK.
2. Apply the same `.rvp` without editing its version number.
3. Every fingerprint must resolve to exactly one injection point.
4. Run static verification and install through `adb install -r`.
5. Repeat each changed user flow on a real ARM64 device.

If Microsoft changes a touched code path, the build stops at that fingerprint.
It never silently patches a merely similar method. Obfuscated class and method
names are not pinned, so ordinary re-minification does not require rewriting the
mod.

A successfully repackaged APK is not considered proof of correct UX; final
validation happens on a physical phone.

</details>

<details>
<summary><strong>GitHub Actions, Releases, and ReVanced Manager</strong></summary>

Run **Build Edge ReVanced APK** manually through **Run workflow**. It:

1. discovers the latest Edge Canary version;
2. downloads the monolithic `arm64-v8a` APK;
3. verifies package name, ABI, and Microsoft’s certificate;
4. builds the `.rvp`, applies patches, and signs the APK;
5. verifies name, icon previews, splash resources, version, signing identity,
   and the preference-backed new-tab DEX contract;
6. publishes the APK and `.rvp` as separate Actions artifacts and Release
   assets.

A push to `main` runs the heavy build only when no Release exists for the
discovered Canary version. A manual run always rebuilds the latest version and
updates the existing Release.

GitHub Actions artifacts are always downloaded as ZIP files. For direct
installation, use `edge-revanced.apk` from the Release.

ReVanced Manager uses the same Patcher on Android, but Edge ReVanced currently
supports the PC pipeline. DevTools adds hundreds of resources and requires a
full recompile against Android SDK framework 37 with a compatible `aapt2`; the
stock Manager does not receive that environment contract from this repository.

</details>

## Verified compatibility

Patches and user flows have been tested with ARM64 builds:

- Edge Canary `152.0.4180.0`;
- Edge Canary `152.0.4184.0`.

Latest full device test: **OnePlus 13, Android 16**.

## License

Edge ReVanced is licensed under [GPLv3](LICENSE).
