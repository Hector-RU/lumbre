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
large-scale fonts and different Android System WebView versions. Ordinary Gradle release output is unsigned. The separate signed 1.1 artifact is documented
in the 3 October entry; signing a new distribution requires the owner's existing key.


## 15 October 2026 — persistence, search, links and portable backup

Environment: Pixel 8 AVD, Android 17 / API 37, x86_64, 1080 × 2400;
Android System WebView `149.0.7827.5`.

| Check | Result |
| --- | --- |
| JVM (`testDebugUnitTest`) | 28 passed, 0 failed |
| Android (`connectedDebugAndroidTest`) | 19 passed, 0 failed, 0 skipped |
| Debug Lint (`lintDebug`) | 0 errors, 20 warnings |
| Release Lint (`lintRelease`) | 0 errors, 20 warnings |
| Debug / ordinary release | APKs built; ordinary release remains unsigned |

Coverage added in this iteration:

- Room opens the exported v1 schema, migrates to v2 and retains existing progress and bookmarks.
- UTF-16 offsets remain consistent through nested inline markup, paragraph transformation and pagination.
- SVG text remains native markup in all three modes; no HTML locator wrapper is inserted into graphics.
- Search returns multiple occurrences per chapter and can restore a selected occurrence beyond the first.
- Reader recreation retains a saved character across scroll/pages/paragraphs, larger margins and text,
  including a simulated system font scale of 200%. The test checks that the character is on screen.
- Reflow retains the intended character instead of repeatedly replacing it with the beginning of a page.
- WebView publishes a forward accessibility action and uses it to advance the reading position.
- Settings choices expose radio-group, role and selection semantics. UI tests use localized strings.
- Supplementary contents remain outside the main reading order and internal link callbacks accept them.
- With reader JavaScript enabled, real WebView loads in all three modes strip publication scripts and
  event handlers; remote requests receive 403 and external navigation is intercepted.
- ZIP backup/restore preserves titles, positions, text locators, bookmarks and preferences. Repeating
  restore keeps existing duplicates and their progress. Unsafe backup paths are rejected.

These checks use synthetic/original fixtures, not a broad corpus of third-party publications.
The user subsequently confirmed that the proposed manual compatibility, accessibility and backup
checks worked correctly; see the manual acceptance entry below. Device/API/WebView versions,
EPUB fixtures and document providers were not specified. The release checklist is in `ROADMAP.md`.

Commands (PowerShell, with the configured JDK):

```powershell
.\gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease connectedDebugAndroidTest
```

Generated reports: `app/build/reports/tests/testDebugUnitTest/index.html`,
`app/build/reports/androidTests/connected/debug/index.html`,
`app/build/reports/lint-results-debug.html` and `app/build/reports/lint-results-release.html`.
Local execution logs are under ignored `test-artifacts/`: `iteration-final.log` records the full
Android suite; `iteration-packaging.log` records the final JVM/Lint/APK pass after the SVG-only
preservation guard. That guard is checked by the JVM DOM regression; broader visual SVG validation
remains part of the manual EPUB corpus review.

During development, the 200% font-scale test exposed a distinction between Chromium's layout and
visual viewports. The final capture uses visual viewport bounds, and accessible page turns retain
new text positions. Interim failures are resolved; no test or Lint rule was disabled.


## 15 October 2026 — manual acceptance reported by the user

After the proposed EPUB compatibility, TalkBack/large-text/navigation and backup/restore checks,
the user stated that the tests were completed and that everything worked correctly. No issues
were reported. This records the user's acceptance of the tested flows, separately from the
automated run above.

The specific devices, Android/WebView versions, books and document providers were not supplied;
no additional matrix coverage is inferred. The next delivery step is selecting the new version,
producing the release with the existing signing key and verifying its certificate and checksum.


## 15 October 2026 — Lumbre 1.2 signed release

Android versionName `1.2`, versionCode `3`, applicationId `com.hector.epubreader`.
The APK is in `app/build/outputs/release-v1.2/lumbre-1.2.apk` and uses the existing 1.1 signing key.

| Check | Result |
| --- | --- |
| JVM (`testDebugUnitTest`) | 28 passed, 0 failed |
| Android (`connectedDebugAndroidTest`) | 19 passed, 0 failed, 0 skipped |
| Debug / release Lint | 0 errors, 20 warnings per variant |
| Debug / release Gradle builds | Passed |
| Signed package metadata (`aapt`) | Correct applicationId, versionName 1.2 and versionCode 3 |
| APK signatures (`apksigner verify`) | v2/v3 valid; certificate matches signed 1.1 |
| ZIP alignment (`zipalign -c -P 16 -v 4`) | Passed |

Certificate SHA-256: `09363ff5326e5eff4808da394cececf804e271fe803ee832e59f30710c948f7c`.
APK SHA-256: `8f70075dc7e6e6e97d3e4e5a38c5f636c3a29c3be4a8ab4bce9cc653e795a4a2`.
APK size: 16112595 bytes. The sibling `lumbre-1.2.apk.sha256`, `release.json`,
`signature-verification.txt`, `alignment-verification.txt` and `release-notes.md` document the artifact.
The ordinary Gradle release APK remains unsigned; signing material stays outside the repository.

Environment: the same Pixel 8 AVD / Android 17 / API 37 and WebView 149.0.7827.5 used above.
The initial automated run found TalkBack still enabled after manual acceptance and had two failures
in scripted gesture/accessibility-host checks. The resize/turn regression passed in isolation.
For the final full gesture suite, accessibility settings were saved, TalkBack was temporarily disabled,
and the original enabled service and setting were restored in a `finally` block and checked afterward.
That controlled run passed all 19 tests. This is separate from the user's successful manual TalkBack
acceptance; no test or assertion was disabled or weakened.

Lint review: 13 dependency-update notices, four KTX style suggestions, one API-specific localeConfig
attribute notice, one unused string and one JavaScript-enablement notice. The reader-owned JavaScript
is covered by the publication-code blocking tests and documented CSP/network restrictions. Warnings
remain visible; no dependency migration or Lint suppression was introduced for this release.

Local logs: `test-artifacts/release-1.2-verification.log` contains the build/JVM/Lint run and initial
Android failures; `release-1.2-android-final.log` records the final complete passing Android run.
Manual acceptance remains as reported in the preceding entry.


## 15 October 2026 — fonts and annotations (unreleased)

The next iteration adds standard IDPF font obfuscation, persistent highlights/notes, Room v3
and portable backup v2. It does not modify the signed 1.2 distribution or claim manual acceptance
for these new features. The application version remains unchanged pending the next release.

New automated coverage:

- The font decoder uses an independent SHA-1 test vector, mixed single/bulk reads, skip across
  the 1040-byte boundary, short fonts and unchanged font tails. Parser tests select the package's
  referenced identifier, strip XML whitespace and reject DRM/non-font obfuscation.
- A real WebView loads a device font that the test obfuscates and embeds in a temporary EPUB.
  The font's FontFace status reaches loaded with network loads disabled. No system font file is
  redistributed in the repository.
- Overlapping highlights preserve canonical text/offsets across inline markup and all reading modes.
  Native ActionMode selection yields the expected UTF-16 range; saved highlights remain visible
  after recreation, larger type and changes between scroll/pages/paragraphs.
- A real long press in page mode opens the native reader action, followed by the Compose editor.
  The UI test saves, edits and deletes a note and checks the resulting repository state.
  Android 17's floating toolbar is rendered in a remote SurfaceView. Coordinate-based toolbar
  tapping proved unstable in the initial full run. The final test uses a real long press and
  invokes the selected-text accessibility action, then waits for the asynchronous editor.
  The native ActionMode menu callback is covered separately by AnnotationReaderTest.
- Room migrations from v1 and directly from v2 retain books, bookmarks and progress, then allow
  annotations to be written. Deleting a book cascades to its annotations.
- Backup v2 round-trips quotes, ranges and Unicode notes. Restoring a duplicate keeps later edits.
  Overflowing ranges are rejected before importing any books. A converted v1 archive restores
  progress without requiring an annotations array. Unsafe ZIP paths remain rejected.

Environment: Pixel 8 AVD, Android 17/API 37, WebView 149.0.7827.5. Accessibility was already
inactive at the start of this iteration's tests; its initial state was saved locally. The final
run uses that state. Manual review with TalkBack, older Android/WebView versions and a wider
publication corpus remains pending for these additions; the prior 1.2 acceptance is historical.

JVM coverage: 34 passing tests. Debug/release builds succeed. Lint reports 0 errors and
21 warnings per variant: the prior 20 notices plus AlwaysShowAction for the reader's primary
selection action. This keeps highlighting directly available alongside native text actions;
no Lint check or test was suppressed. The final complete Android result is recorded below.

Logs: ignored `test-artifacts/annotations-final-verification.log` (full checks),
`annotations-ui.log` (native selection/editor flow) and the standard Gradle HTML/XML reports.


Final complete run: **passed**, 4 minutes 13 seconds, with no tests skipped.

| Check | Result |
| --- | --- |
| JVM `testDebugUnitTest` | 34 passed, 0 failed |
| Android `connectedDebugAndroidTest` | 23 passed, 0 failed, 0 skipped |
| Debug / release Lint | 0 errors, 21 warnings per variant |
| Debug / release builds | Passed |

Command: `./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease connectedDebugAndroidTest`
(using `gradlew.bat` and the configured Android Studio JDK on Windows).
The earlier full-run toolbar-coordinate failure is retained in the ignored
`test-artifacts/annotations-initial-full.log`; the final log above supersedes that result.
All original assertions remain enabled. New features still need manual corpus/TalkBack acceptance
before a new signed release is prepared.


## 15 October 2026 — annotation appearance (unreleased)

Room v4 adds explicit style/color defaults for existing annotations. Backup v3 includes those
fields, validates their IDs and restores v1/v2 archives with the original appearance. The editor
provides shading, underline or both, five named colors, selection semantics and a live passage
preview. Annotation cards separate the passage and note, identify chapter/style/color, expand
long text and retain edit/delete/navigation actions. Underline-only marks preserve the reader's
text color; shaded marks use a contrasting ink/background pair. A line drawn over a shaded
background uses a darker color in either reading theme.

New verification:

- JVM rendering coverage exercises all 90 combinations of three reading modes, two theme types,
  three styles and five colors. Text offsets and original content remain unchanged. A separate
  overlap regression checks independent blue shading and red underlining.
- Migration coverage now starts at schemas 1, 2 and 3. Version 3's existing annotation retains its
  note and receives combined/yellow defaults during migration to v4.
- Backup v3 preserves selected styles/colors. An invalid CSS-like color ID is rejected before
  library changes. Converted v2 annotations receive the original combined/yellow appearance;
  converted v1 archives still restore without annotations.
- UI coverage selects purple underlining, saves/reopens it, changes it to blue shading, checks
  computed WebView styles, then deletes the annotation. A separate editor test uses 200% font
  scale and a dark reading palette, chooses combined green marks, enters a note and saves with
  the keyboard visible. Color choices expose radio selection states and names.
- Emulated screenshots were inspected for the compact editor, color controls and large-text
  preview. The preview's underline is painted above its background to remain visible in combined
  mode. Cards use a visible border and plural-aware counts; controls wrap/scroll when space shrinks.

Environment: Pixel 8 AVD / Android 17 API 37 / WebView 149.0.7827.5. Initial accessibility remains
inactive; no physical-device or manual TalkBack acceptance is inferred. Screenshots and logs are
under ignored `test-artifacts/annotation-style-*`. The complete final result is recorded below.


The first combined run attached Android's UiAutomation accessibility service while taking
screenshots in the new UI test (`accessibility_enabled` became 1 with no external service).
Three existing resize/controls/search assertions failed in that altered environment. Screenshot
capture is now explicitly opt-in (`annotationScreenshots=true`) for a separate visual-only run;
normal regression tests do not initialize UiAutomation. The original inactive accessibility state
is retained and checked during the final regression run. No assertion or test was disabled.
A Spanish plural quantity notice found during Lint was corrected with an explicit `many` form.

Build/JVM/Lint log: `test-artifacts/annotation-style-packaging.log`.
Final Android log: `test-artifacts/annotation-style-android-final.log`.
Visual-only log: `test-artifacts/annotation-style-visual-final.log`.


Further isolation showed that native find could pan back to its first match even after
visual-state callbacks: the selected locator was correct but the viewport had returned near
the start. The selected occurrence is now rendered as a temporary reader-owned yellow shade;
native find is no longer involved. A visual-state callback commits the restored viewport before
readiness. The search test checks both the original visibility/offset conditions and the actual
mark color, within the existing 5-second bound, with geometry diagnostics on failure.

Synthetic reader taps/swipes now deliver their intended 40/80 ms timestamps in one UI-thread
turn; separate host round trips could otherwise stretch a simulated short tap into a long press.
All original controls, snapshot, position and navigation assertions remain enabled.

The final native instrumentation run is kept separate from compilation/Lint. The Gradle daemon
is stopped first to release build memory (less than 1 GiB was initially free on this host).
`annotation-style-android-final-native.log` is the final Android log; earlier Gradle-generated
Android reports retain their earlier attempt results and should not be read as the final run.


Final verification of annotation appearance:

| Check | Result |
| --- | --- |
| JVM `testDebugUnitTest` | 36 passed, 0 failed |
| Native AndroidJUnitRunner, complete suite | 25 passed, 0 failed, 0 skipped (465.423 seconds) |
| Debug / release Lint | 0 errors, 21 existing warnings per variant |
| Debug / release builds | Passed |

The final Android run used the APKs produced by the successful packaging/JVM/Lint pass, with
the Gradle daemon stopped and screenshot capture disabled. This final full passing run supersedes
the earlier failed attempts; the earlier generated AGP Android HTML/XML reports are historical.
Machine-readable summary: ignored `test-artifacts/annotation-style-validation.json`.
Visual captures run separately with `-e annotationScreenshots true` and an explicit UI test method;
that opt-in run does not change the APK or the regression assertions. These features remain
unreleased, with manual physical-device/TalkBack acceptance pending.


The separate opt-in visual run also passed its editor/create/edit/delete test (1 passed,
56.234 seconds). Final captures were pulled and inspected: the compact editor and bordered
annotation card show the selected style/color, plural-correct count, distinct passage/note and
navigation/edit/delete actions; the reader behind the card reflects the saved blue shading.
The emulator's original inactive accessibility setting and empty enabled-services value were
restored after capture. PNGs remain in ignored `test-artifacts/annotation-style-editor.png`,
`annotation-style-list.png` and the earlier large-text capture.

## Lumbre 1.3 implementation verification — 15 October 2026

The six roadmap stages are implemented: release preparation, recoverable backups,
compatibility coverage, bounded text caching and highlight rendering, reader separation/CI,
and annotation export, collection filters and foreground offline TTS.

| Check | Result / evidence |
| --- | --- |
| JVM | 42 passed, zero failures/errors/skips |
| Debug/release Lint | 0 errors, 21 warnings per variant |
| Debug/release and test APK builds | Passed; `test-artifacts/roadmap-corpus-final-build.log` |
| Android 8 / API 26 | 33 passed in 114.488 seconds; `test-artifacts/roadmap-api26-complete-final.log` |
| Android 17 / API 37 | 33 passed in 551.811 seconds; `test-artifacts/roadmap-api37-complete-confirmed.log` |
| Publisher corpus | 62 chapters in three rendering modes; details and hashes in COMPATIBILITY.md |
| Release signature | Same certificate as 1.2; v2/v3 verified, 16 KiB alignment verified |

The 1,000-paragraph synthetic rendering regression (65,903 source bytes, 871 annotations)
recorded 339 ms for scroll, 188 ms for pages and 235 ms for paragraphs on this host/JVM.
These are local sample timings with exact marker/offset assertions, not a device performance
guarantee or a measured speedup over the previous release. LRU bounds/eviction and sweep
equivalence to brute force are checked independently.

API 26 ran the official AOSP x86 image with its bundled WebView 58.0.3029.125,
720×1280 at density 320. A resource-only framework overlay registers the existing AOSP
provider; SELinux stays enforcing. API 37 uses WebView 149.0.7827.5 at 1080×2400.
Instrumentation uses the deterministic test TTS engine; audible physical voice quality is
not established by these tests. Screenshot UiAutomation is disabled during regressions.

Legacy compatibility fixes preserve stable insets across immersive controls and account
for Chromium 58's layout/visual viewport coordinate difference. Geometry assertions now
use the correct native viewport on engines without VisualViewport. The actual last-text,
page-boundary, reflow, locator and gesture checks remain enabled. A temporarily absent
accessibility node is retried until the existing action-availability deadline.

Signed candidate: `app/build/outputs/release-v1.3/lumbre-1.3.apk`, versionCode 4,
16,219,091 bytes; source commit `c164dd45d6764d577c1cba895c9a1950f87a5c2e`
with a clean working tree at signing. Later verification documentation does not alter it.
SHA-256: `9b16a2170254a58a687278831550233b34ada378d0429875e07289e7924a29e3`.
Certificate SHA-256: `09363ff5326e5eff4808da394cececf804e271fe803ee832e59f30710c948f7c`.
The adjacent APK JSON, checksum, signature and alignment files provide packaging evidence.

The final signed 1.2 → 1.3 update passed both phases of the independent Java instrumentation
probe on the isolated API 26 emulator. Schema 2 migrated to 5; the EPUB, chapter 1,
fraction 0.4, UTF-16 locator 1234 and bookmark survived, with an empty initial collection.
Evidence: `test-artifacts/roadmap-signed-upgrade-final.log` and `signed-upgrade-prepare.log` /
`signed-upgrade-verify.log`. The signed app's library rendered successfully after the update;
its ADB screenshot is `test-artifacts/lumbre-signed-library.png`. The isolated emulator was
then stopped; the user's API 37 emulator was retained.

Physical TalkBack, audible offline voices, real-publication visual acceptance and cloud SAF
provider behavior remain manual acceptance gates. Interrupted destination writes and restore
recovery have automated regression coverage; those checks do not establish provider-specific
behavior. The workflow is prepared locally; remote Actions and publication have not run.
Earlier failed native logs and AGP-generated Android reports remain historical, not final evidence.
The earlier API 37 attempt reported a transient null accessibility node and an activity close
timeout; it was stopped and its log retained. The final run retried the nullable node and ran
with the emulator's original inactive accessibility setting and empty enabled-services value,
both confirmed after completion. All 33 tests passed with no skips or disabled assertions.
Machine-readable final summary: `test-artifacts/roadmap-validation.json`.

## 19 October 2026 — Lumbre 1.4 release

Android versionName `1.4`, versionCode `5`; Room v5 and backup v4 are unchanged.

| Check | Result |
| --- | --- |
| JVM tests (`testDebugUnitTest`) | 46 passed, 0 failures/errors |
| Debug and release Lint | 0 errors / 27 warnings per variant |
| Debug, release and instrumentation APK builds | Passed |
| Targeted Android regression tests | 8 passed on emulator API 37 |
| Release APK metadata | `com.hector.epubreader`, versionName `1.4`, versionCode `5` |
| APK signatures | v2/v3 verified; certificate matches signed 1.3 |
| APK alignment | Verified with `zipalign -c -P 16 -v 4` |

The targeted device run includes `ReaderSpeechTest`, `SpeechHighlightTest` and
`SpeechPlayerTest`: playback completion, unsupported-language handling, voice fallback,
sentence/chapter navigation, preserving paused narration against stale WebView captures,
non-destructive sentence shading, rendered-book continuity and reachable player controls.
The opt-in real-engine `DeviceSpeechTest` was not run.

Build evidence: `test-artifacts/release-1.4-build.log`.
Device evidence: `test-artifacts/release-1.4-speech-tests.log`.
Packaging evidence: `app/build/outputs/release-v1.4/`, including the signed APK,
SHA-256 checksum, signature/alignment reports and source metadata. Signing material remains
outside the repository. The certificate SHA-256 remains
`09363ff5326e5eff4808da394cececf804e271fe803ee832e59f30710c948f7c`.

This local run is targeted verification of the 1.4 changes. The full Android suite, API 26,
a signed upgrade with populated user data, physical-device TalkBack and audible voice quality
were not rerun for this release; earlier 1.3 results above remain historical evidence.

## 22 October 2026 — integration audit of published 1.4

Before integration, `main` was `57531e6a627675a5cf2010793d36ae5f578655b3` and
`v1.4` / `codex/lumbre-1.3-improvements` were
`962caac1e1b29a55efc94da98b26d1719910e295`: one commit ahead, with no divergence.
The integration branch retains that original release commit as an ancestor.
Android configuration and packaging agree on versionName `1.4`, versionCode `5`,
minSdk `26` and targetSdk `37`; no app, schema or backup changes are needed for integration.

[Android verification run 39319038583](https://github.com/Hector-RU/lumbre/actions/runs/39319038583)
ran on the exact published commit on 19 October. The build job successfully ran JVM tests,
debug/release Lint and debug/release/instrumentation APK builds. The API 26 device job
reported success, but its JUnit report contains 40 tests: 39 passed and the opt-in
`DeviceSpeechTest` assumption was recorded as a failure rather than a skip. The first PR
run reproduced that same result. The API 37 tag job also appeared green, but its log states
`Failed to install APK` and `Requested internal only, but not enough space`.
That job is not evidence of passing device tests. The 8 locally verified API 37 speech
tests above remain separate evidence; no full API 37 CI pass is claimed.

The integration workflow allocates a 6 GiB emulator data partition and checks that JUnit
reports contain successfully executed tests and no failures/errors. Missing reports, empty
suites and all-skipped runs fail the check. Automatic device runs explicitly exclude
`DeviceSpeechTest`; real-engine validation remains available by selecting that class with
`-Pandroid.testInstrumentationRunnerArguments.realSpeech=true` on a prepared device.
Device jobs also run on pull requests so that integration receives fresh API 26/37 results.
Physical TalkBack, audible engine quality and
signed upgrades with populated data remain outside these automated checks.

The initial PR API 37 job and one isolated retry failed before tests with
`Can't find service: package`, unlike the release's earlier storage failure. The workflow
now builds the device APKs before emulator startup, stops the build daemon and gives the
emulator 4 GiB RAM. Device logcat and storage diagnostics are retained with test reports.
These infrastructure failures are not application test results.

The next API 37 run installed the app but began 39 tests while Android user storage was
still locked (logcat: `user not unlocked`); 38 failed to resolve activities or access data.
The device script now waits for user 0 to reach `RUNNING_UNLOCKED` and for the package
service to respond on three consecutive checks, with a 180-second limit. Boot completion
alone is insufficient readiness evidence for this emulator image.

Deeper logcat inspection identifies recurring SurfaceFlinger aborts in `mapper.ranchu`
with `hasReadColorBufferDma`, restarting the Android framework. The action prepends the
runner's preinstalled Command-line Tools 12.0 through `cmdline-tools/latest`, overriding
the newer tools supplied by setup-android. The same crash and malformed API 37 AVD targets
are reproduced in [the upstream action issue](https://github.com/ReactiveCircus/android-emulator-runner/issues/482).
CI now pins Command-line Tools 22.0 (archive `15859902`), points `latest` at those tools,
uses software graphics and verifies the generated AVD target before launch. The original
runner tools are retained in a separate directory for the lifetime of the CI job.

The published `v1.4` tag, release body, signed APK and checksum are retained unchanged.
The published APK asset digest is
`72de99fbc38c1bc853a7363876bc7705801191b11a5a059e236f1860a87293f7` (SHA-256).

Fresh local integration checks on 22 October passed: 46 JVM tests with no failures,
errors or skips; debug/release Lint; and debug, release and instrumentation APK builds.
The report guard was exercised with missing, empty, all-skipped, failed, runner-error,
malformed, passing-with-skip and nested passing JUnit fixtures; all 8 expectations passed.
No local device was connected, so device execution is delegated to the PR workflow.

## 23 October 2026 — integration gate results

The [integration workflow](https://github.com/Hector-RU/lumbre/actions/runs/39900982165)
tested application source from `4089f9791d012449fdd2b4a7aee935c1352f56cc`.
Its app and packaging sources are identical to published `v1.4`; subsequent audit-only
documentation changes do not alter those sources.

| Check | Observed result |
| --- | --- |
| Build gate: JVM, debug/release Lint and APK builds | Passed; 46 JVM tests |
| API 26 automatic device suite | 39 passed, 0 failed/errors/skipped |
| API 37 automatic suite, first attempt with pinned tools | 38 passed, 1 failed |
| API 37 automatic suite, isolated retry | 37 passed, 2 failed, 0 errors/skipped |
| AVD target validation | `android-26` and `android-37.0` verified |
| Downloaded published APK | Attached SHA-256 matches; alignment and v2/v3 signatures verified |

API 37 failures in the retry:

- `AnnotationUiTest.longPressOpensEditorAndNotesCanBeSavedEditedAndDeleted` times out
  at line 109 waiting for the annotation accessibility action after the native long press.
- `ReaderGestureTest.horizontalModesShowCompleteLastPage` reports
  `pages last page is clamped: x=39897, count=38, stride=1080.0`.

Neither test was removed or relaxed. These are unresolved device gates, distinct from the
earlier emulator setup failures. At the time of this run,
[PR #1](https://github.com/Hector-RU/lumbre/pull/1) remained a draft. It was subsequently
merged on 23 October; see the repository audit below. Product changes to resolve these
failures are outside the published 1.4 commit retained by the integration.

The downloaded APK also reports versionName `1.4`, versionCode `5`, minSdk `26` and
targetSdk `37`, and certificate SHA-256
`09363ff5326e5eff4808da394cececf804e271fe803ee832e59f30710c948f7c`.
Its SHA-256 remains `72de99fbc38c1bc853a7363876bc7705801191b11a5a059e236f1860a87293f7`.
The release body, update timestamp, asset IDs, sizes and digests were compared before and
after the audit and are unchanged.

## 25 October 2026 — repository and regression audit

Fetched `origin` and verified that published 1.4 commit
`962caac1e1b29a55efc94da98b26d1719910e295` is an ancestor of `origin/main`
(`47db6361e445bce5a341c327bdc64a9f42d95a36`). There are no application-source
differences between those commits. The original local branch was clean and matched
its remote; audit documentation is maintained on `codex/verification-audit`, based
on current `origin/main`.

[PR #1](https://github.com/Hector-RU/lumbre/pull/1) merged on 23 October 2026.
The [main push workflow](https://github.com/Hector-RU/lumbre/actions/runs/39930331087)
passed the build gate but skipped devices. The
[final PR workflow](https://github.com/Hector-RU/lumbre/actions/runs/39903521027)
passed build and API 26, but failed API 37. Therefore the main workflow's green
status does not establish a passing full device matrix. The 23 October counts and
named failures above belong to run `39900982165`, not the later final PR run.

Fresh local checks use JDK 25, the existing project SDK and the unchanged app source:

| Check | Observed result |
| --- | --- |
| JVM, forced task execution | 46 passed, 0 failures/errors/skips |
| Debug/release Lint | 0 errors, 28 warnings per variant |
| Debug/release/instrumentation APK builds | Passed |
| Local API 37 automatic suite | 38 passed, 1 failed, 0 errors/skips; 39 tests, 264.396 seconds |

Command: `gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest --rerun-tasks --console=plain`.
All 139 tasks executed; build completed successfully in 3m 9s.
Build evidence: ignored `test-artifacts/audit-2026-10-25-build.log`; machine-readable
JVM reports: `app/build/test-results/testDebugUnitTest/`; Lint XML/HTML:
`app/build/reports/lint-results-{debug,release}.*`.

The connected local emulator is Android 17 / API 37, serial `emulator-5554`,
WebView `149.0.7827.5`, with accessibility initially inactive and user 0 unlocked.
The automatic suite excludes only opt-in `DeviceSpeechTest`, matching CI.
Physical audible voices, TalkBack, cloud SAF providers and a signed upgrade with
populated user data are not established by this audit.

Connected command: `gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.hector.epubreader.DeviceSpeechTest --console=plain`.
The task correctly exited with failure. `AnnotationUiTest.longPressOpensEditorAndNotesCanBeSavedEditedAndDeleted`
timed out at line 109 after 10 seconds waiting for the selected-text accessibility
action following a native long press. No assertion was removed or relaxed.
`ReaderGestureTest.horizontalModesShowCompleteLastPage` passed locally; this does not
resolve or supersede its earlier CI failure. Accessibility remained inactive after
the run. API 26 was not rerun locally.

Device evidence: ignored `test-artifacts/audit-2026-10-25-android.log`,
`app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_8(AVD) - 17.xml`
and `app/build/reports/androidTests/connected/debug/index.html`.
At the end of this initial audit, repository changes were documentation only and
not all tests had passed. The annotation regression and API 37 CI gate remained
open; the subsequent touch-input correction and local results are recorded below.

## 25 October 2026 — explicit touch input for annotation regression

The annotation UI test used Espresso's deprecated three-argument `GeneralClickAction`
constructor. Inspection of the installed Espresso 3.7.0 source confirms that it passes
input source `0` (`SOURCE_UNKNOWN`) and button state `0`; `MotionEvents` consequently
assigns `TOOL_TYPE_UNKNOWN`. `Press.FINGER` controls coordinate precision, not the
pointer's tool type. The test now explicitly supplies `InputDevice.SOURCE_TOUCHSCREEN`
and button state `0`, which makes Espresso construct a finger touch event.

This removes ambiguous synthetic input from a test of native touch selection. Production
reader code, timeouts and all existing selection/editor/persistence/rendering assertions
are unchanged. No JavaScript selection, manually opened ActionMode or retry of the
long press replaces the native gesture. Temporary diagnostic logging/reflection was
removed before building the final APKs.

The pre-fix failure was intermittent: diagnostic runs reproduced a selection with no
active annotation action, while a later control run passed all 39 automatic tests.
Passing that control run alone was not treated as a fix. With explicit touch input,
the full create/edit/delete regression passed five consecutive native runner invocations
on API 37. These observations support the input correction; they do not prove a
specific Chromium-internal failure mechanism or guarantee all future runs.

The final build/JVM/Lint/APK pass completed successfully in 1m 19s. JVM remains
46 passed with no failures/errors/skips (unchanged JVM inputs reused Gradle results),
and debug/release Lint reports 0 errors / 28 warnings per variant. The final test APK
was rebuilt and installed on both test emulators. The Gradle daemon was stopped
before native instrumentation to release build memory.

| Final native device check | Observed result |
| --- | --- |
| API 26 / AOSP x86 / WebView 58.0.3029.125 | 39 passed, 0 failed/errors/skipped; 184.191 seconds |
| API 37 / Pixel 8 x86_64 / WebView 149.0.7827.5 | 39 passed, 0 failed/errors/skipped; 344.449 seconds |

The isolated API 26 emulator was stopped after validation; its accessibility setting
remained inactive. The existing API 37 emulator is retained.
API 37 also retained inactive accessibility and an empty enabled-services setting.
Both full automatic device suites passed with the final corrected test APK, including
annotation creation/editing/deletion and last-page clamping.

Evidence under ignored `test-artifacts/`: `annotation-fix-build.log`,
`annotation-fix-touch-repeat-{1,2,3,4,5}.log`, `annotation-fix-api26-full.log`
and `annotation-fix-api37-full.log`. Device suites exclude only opt-in
`DeviceSpeechTest`, matching CI. Screenshot UiAutomation remains disabled.
Earlier audit/CI failures above remain historical; local results do not replace
a fresh remote API 37 CI gate or physical-device acceptance.

Integration: commit `52851db232d0e4535cab905bc194f6bc34d5c260`, containing the
explicit-touch correction and the results above, was fast-forwarded into `main`
on 25 October. Integration adds no further application/test changes; the subsequent
documentation update only records this status. A push to `main` runs the build gate;
the workflow's device jobs require a PR, tag or manual dispatch.
