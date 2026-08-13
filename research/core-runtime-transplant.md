# Edge core/runtime transplant experiment

## Question

Can Edge ReVanced keep an old, heavily customized Java/UI shell and update only
the Chromium runtime taken from a newer Edge Canary APK?

The experiment intentionally starts with the smallest plausible donor bundle:

- `lib/arm64-v8a/libchrome.so`;
- `lib/arm64-v8a/libcrashpad_handler_trampoline.so`;
- `assets/resources.pak`;
- `assets/snapshot_blob_64.bin`;
- `assets/v8_context_snapshot_64.bin`.

This is research, not a supported release path. The tested recipient was Edge
Canary `152.0.4184.0`; the first donor was `152.0.4188.0`.

## Historical evidence

Seven monolithic ARM64 APKs were compared in chronological order:

| Edge Canary | `J.N` methods | `J.N.WHOLE_HASH` | Display `VIJ` selector | Sync constructor `VJO` selector |
| --- | ---: | ---: | ---: | ---: |
| 147.0.3891.0 | 318 | 2807823945818755800 | 95 | 247 |
| 150.0.4067.0 | 330 | 6740961445209045953 | 98 | 263 |
| 151.0.4129.0 | 330 | -7254508019386152508 | 102 | 261 |
| 152.0.4184.0 | 333 | 2225220946159866515 | 107 | 268 |
| 152.0.4188.0 | 333 | 8346909227300391081 | 108 | 269 |
| 153.0.4197.0 | 334 | 8346909227300391081 | 108 | 269 |
| 153.0.4213.0 | 333 | 3149728619296251691 | 108 | 272 |

The important part is not the obfuscated names. `J.N` is a generated Java JNI
multiplexer. Its method signature set can stay identical while `WHOLE_HASH` and
the integer selectors embedded across thousands of Java call sites change.
Native-to-Java callback contracts can change independently too.

APK entry hashes also show that every tested adjacent update changed all six
DEX files. Between `152.0.4184.0` and `152.0.4188.0`, 2,519 ZIP entries changed
despite the four-build-number distance. The three runtime snapshots/paks and
seven native libraries changed.

The inventory can be reproduced without decompiling the application:

```powershell
.\scripts\analyze-edge-apks.ps1 `
    -Apk @(
        'C:\path\to\old-edge.apk',
        'C:\path\to\new-edge.apk'
    ) `
    -Output 'local\edge-apk-diff.json'
```

## Runtime rungs

1. Replacing the five donor entries while recompressing `resources.pak`
   failed immediately: Chromium opens that asset with `AssetManager.openFd`,
   which requires an uncompressed ZIP entry.
2. Preserving every donor entry's compression mode allowed the new native
   library to load. Reaching onboarding was not sufficient evidence because the
   real browser activity had not initialized yet.
3. On browser initialization, the old Java layer called
   `DisplayAndroidManager` through `J.N.VIJ(107, ...)`. The donor native table
   expected selector `108` and deliberately trapped in `Java_J_N_VIJ`.
4. Adapting that selector reached the next boundary. The old
   `SyncServiceImpl` constructor used `J.N.VJO(268, ...)`; the donor expected
   `269`. Native code consequently routed its arguments to the wrong Java
   callback, and a `String[]` was treated as `org.chromium.base.Callback`.
5. Adapting both known selectors moved execution further, then produced a
   native background-thread trap. The x86_64 AVD used ARM translation and died
   while the unmodified donor control was being installed, so this last result
   is inconclusive until repeated on a real ARM64 device.

## Boundary discovered by the experiment

`libchrome.so` is not an independently replaceable browser engine in Edge's
Android package. A compatible runtime unit contains at least:

```text
native Chromium library and snapshots
                +
generated J.N multiplexer and selector call sites
                +
native-to-Java callback classes and signatures
                +
their transitive Chromium Java dependencies
```

In the measured APKs, `org.chromium.*` alone is about 5,000 classes and 60,000
methods. Those classes also reference version-specific obfuscated `defpackage`
types. Moving the whole closure therefore approaches transplanting the complete
Chromium Java layer, not swapping a small engine component.

## Architecture decision

Do not turn the five-entry transplant into a release pipeline. It is useful as
a compatibility probe, but automatically modifying hashes or selector numbers
would hide ABI mismatches and produce late, unsafe crashes.

For a heavily customized Edge ReVanced, the maintainable direction remains:

1. use each new Edge APK as the complete Chromium/native/Java base;
2. keep product behavior in owned injected runtime code where possible;
3. replace whole UI surfaces at a small number of stable boundaries rather than
   accumulating many edits inside obfuscated vendor implementations;
4. keep static ReVanced fingerprints structural and fail fast when a boundary
   changes;
5. validate every newly supported Edge build on a physical ARM64 device.

This does not eliminate update work, but it keeps the large volatile vendor
closure intact and makes our owned UI/features the stable layer. A true engine
fork would instead require building and maintaining Chromium (or another
open-source Chromium browser) from source; a decompiled closed Edge APK cannot
be converted into an equivalent maintainable source fork.
