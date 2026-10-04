# Verification log

Date: 24 September 2026 (America/Santo_Domingo).

## Observed results

| Check | Result |
| --- | --- |
| Original build | Passed |
| JVM tests | 19 passed, 0 failed |
| Android tests, AOSP 35 / API 35 / x86_64 / WHPX | 5 passed, 0 failed |
| Lint debug | 0 errors; 15 warnings about newer dependency versions |
| Debug APK | Built and installed on the emulator |
| Release APK | Built without a publishing signature |
| SAF → library → reader flow | Verified with `lumbre-demo.epub` |
| Size, theme and table of contents | Changes visible and persisted |
| Force close and reopen | Restored chapter 2, dark theme, font size 26 and the same section of paragraphs 2–3 |
| Search for "esperanza" | Results in both chapters; jump and highlight visible |
| Bookmark | Created, listed and restored in chapter 2 |
| Landscape and portrait | Controls reachable and content rendered |
| Delete from library | Confirmation shown; empty library and the original kept in Downloads (65,206 bytes) |

Screenshots `01` to `13` in `docs/screenshots/` document the run. Comparing the previous and
restored positions showed a difference of about 3 pixels after hiding the system bars again,
keeping the same text. The position is a scroll fraction, not a CFI; textual accuracy across
different geometries is not claimed.

The test infrastructure was fixed during the run: its standalone ContentProvider is implemented
in Java so it does not depend on the Kotlin runtime of the instrumented process.
The application and its features are implemented in Kotlin.

## Build

The original base built before features were integrated. SDK, AGP, Gradle and the package are kept.
Building the application with Compose, Room, DataStore and the reader also passes.

Full verification command:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest
```

`lintDebug` uses no baseline and does not disable error checks. Dependency update warnings stay
visible: Kotlin/AGP/SDK are not migrated just because of them. The WebView gesture warning is
justified locally: `GestureDetector` calls `performClick()` and an accessibility action is
published for the controls.

## Tests

- JVM: EPUB 3 nav, EPUB 2 NCX, spine order, metadata, cover, fallback, corrupted ZIP,
  truncated file, encryption, traversal, ambiguous paths, external entities and read limits.
- JVM: sanitization, relative/encoded resources, preferences, progress and the actually inflated
  byte limit, including reads performed through `skip`.
- Android: real import through the test APK ContentProvider, private copy, duplicates, cover,
  search and reopening of the persisted position.
- Android: Room and bookmark cascade; empty library and theme switching in Compose.
- Android: WebView without JavaScript or file/network access, scrollable content,
  real height change when the font size increases and position preservation within 0.025 tolerance.

Reports generated in `app/build/reports/tests`, `app/build/reports/androidTests` and
`app/build/reports/lint-results-debug.html`.

## Manual acceptance test

Use `docs/samples/lumbre-demo.epub` and an Android 26 or newer device.

1. Install the debug build and open the empty library.
2. Add an EPUB through SAF; check cover, title and author.
3. Open, read and scroll; hide/show the controls with a central tap.
4. Change font size and theme from the appearance panel.
5. Open the table of contents and navigate to another chapter.
6. Search for "esperanza" and open a result.
7. Add a bookmark, scroll and return from the bookmark list.
8. Leave, close the app and reopen the book; check chapter and position.
9. Rotate the device and check that reading and controls remain usable.
10. Delete the book after confirmation; check that the original EPUB is still available.

## Android test environment

The local SDK included the emulator executable and an incomplete Android 37.2 download,
with no AVDs and no connected devices. A separate, official Android 35 AOSP x86_64 image is
prepared under `test-artifacts/` (ignored by Git). Official distribution SHA-1:
`2d857d170c0d1b827149565da34b3383e5306f7f`.

The script `scripts/Start-TestEmulator.ps1` uses that image and a temporary project AVD,
with WHPX acceleration when available, without touching the user's own virtual devices.
It does not download or accept licenses automatically; the local SDK already has them installed.
When it finishes, the emulator is shut down and the image, downloads and temporary AVD are removed
to free space. APKs, reports, screenshots and scripts are kept; repeating the run requires
a device or preparing a test image again.

## 25 September 2026 — pagination, line snapping and animation

Changes verified in this session:

- Library titles are clipped to a single line with an ellipsis instead of wrapping.
- Pages-mode turns advance by exactly one viewport, snap the landing offset to the
  top of a rendered text line and animate over 240 ms with a `PathInterpolator`, so
  the new page never starts with a half-cut line. `lineTopScript` converts the
  View's physical-pixel offset to CSS pixels with `devicePixelRatio` and converts
  the measured line top back before scrolling.
- The pages-mode instrumented test polls for the asynchronous turn to finish and
  asserts that no text line is cut by the top edge of the viewport.

| Check | Result |
| --- | --- |
| JVM tests | 21 passed, 0 failed |
| Android tests, Pixel 8 / Android 17 / API 37 / network adb | 6 passed, 0 failed |
| Lint debug | 0 errors |
| Debug APK | Built and installed on the device |

Environment notes:

- `androidx.test.ext:junit` 1.1.5 → 1.3.0 and `androidx.test.espresso:espresso-core`
  3.5.1 → 3.7.0. Espresso 3.5.1 reflects on `android.hardware.input.InputManager.getInstance`,
  which Android 17 removed, so `Espresso.onIdle` failed instantly in every Compose UI test.
- The connected run needs the device awake and unlocked: while the keyguard is up the
  WebView never finishes loading and the reader-ready wait times out. The screen
  timeout was restored to 300000 ms after the run.

## 3 October 2026 — Lumbre 1.1

- Settings search, grouped preferences, typography preview and six additional reading palettes.
- Searchable table of contents with the current chapter and subsection navigation.
- Reading colors now follow the selected palette, including WebView color scheme and system bar icons.
- Reader controls overlay a fixed viewport so showing or hiding them preserves the visible text.
- Android versionName 1.1 and versionCode 2.

| Check | Result |
| --- | --- |
| JVM tests (`testDebugUnitTest`) | 24 passed, 0 failed |
| Android tests (`connectedDebugAndroidTest`), Pixel 8 / Android 17 | 13 passed, 0 failed, 0 skipped |
| Release Lint (`lintRelease`) | 0 errors, 19 warnings |
| Release APK (`assembleRelease`) | Built, aligned and signed with the existing Lumbre release key |
| APK verification | v2/v3 signatures verified; certificate SHA-256 `09363ff5326e5eff4808da394cececf804e271fe803ee832e59f30710c948f7c` |

The signed `lumbre-1.1.apk` and its SHA-256 checksum are produced under
`app/build/outputs/release-v1.1/`. Signing material stays outside the repository.

## Verification scope

Automated validation does not replace testing against a wide EPUB collection, TalkBack,
large-scale fonts and different Android System WebView versions. The release is produced without
a publishing signature; that signature requires the owner's key.
