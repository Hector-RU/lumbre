# Lumbre

Current published release: **[1.4](https://github.com/Hector-RU/lumbre/releases/tag/v1.4)**
(Android versionCode `5`). See [release notes](docs/RELEASE_NOTES.md).

Version **1.4** (Android versionCode `5`) adds expanded and compact read-aloud players,
sentence highlighting and navigation, offline voice selection, a sleep timer and refreshed
launcher artwork. See [release notes](docs/RELEASE_NOTES.md) and [verification](docs/VERIFICATION.md).
The signed APK and its SHA-256 checksum are attached to the release. Its signing certificate
matches 1.3, allowing installation as an update. Local packaging evidence is under
`app/build/outputs/release-v1.4/`.

Version **1.3** includes IDPF fonts, configurable annotations,
collections, annotation export, foreground offline TTS, Room v5 and recoverable backup v4
(restoring v1/v2/v3 as well).

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Android EPUB reader written in Kotlin and Jetpack Compose. It provides a private library,
offline reading and a Material 3 interface in Spanish, built on top of the existing
Android Studio project.

## Features

- Import through the Android Storage Access Framework (SAF), with validation and duplicate detection.
- Persistent local copy: the book stays available even if the original file is moved.
- Covers, metadata, grid/list layout, search by title/author and five sorting criteria.
- Reflowable EPUB 2/3: chapters, nav/NCX table of contents, images, CSS, internal links and local fonts, including standard IDPF-obfuscated fonts.
- Immersive reader with a central tap to show or hide controls without moving the text, searchable table of contents, search and bookmarks.
- Long press text to shade, underline or combine both, with five colors and an optional note. A live preview and an annotation list support editing, navigation, expanding long passages/notes and deletion.
- Automatic chapter and text-position saving; progress resumes across typography and reading-mode changes, with fraction fallback for older positions.
- Four font families (including the book's own), size, line spacing, margins and justification.
- Twelve reading themes independent of the app light/dark/system theme and Material You, with searchable settings and a typography preview.
- Book information, mark as read, restart progress and delete only the library copy.
- Manual ZIP backup and restore through SAF, including books, progress, bookmarks, highlights, notes and reading/app preferences.
- Collections, reading-state filters, Markdown annotation export and foreground TTS with installed offline voices.
- No accounts, network, global storage permissions, ads or analytics.

## Build and run

Open this folder in Android Studio and sync Gradle. The `com.hector.epubreader` namespace is kept,
with minSdk 26, compileSdk/targetSdk 37, AGP 9.4.1 and Gradle 9.6.0. Kotlin 2.2.10 ships with AGP.
The daemon uses the project-configured JDK 25; on this machine Android Studio provides it.
`local.properties` holds the local SDK path and must not be versioned.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest lintDebug
.\gradlew.bat assembleRelease
.\gradlew.bat connectedDebugAndroidTest
```

On macOS/Linux, use `./gradlew` and configure a local JDK/SDK. The first build needs network
access to download dependencies. The installed application runs offline.

- Debug: `app/build/outputs/apk/debug/app-debug.apk`, signed with the local Android debug key.
- Ordinary Gradle release: `app/build/outputs/apk/release/app-release-unsigned.apk`.
- Signed 1.4 distribution: download `lumbre-1.4.apk` and `lumbre-1.4.apk.sha256` from the
  [1.4 release](https://github.com/Hector-RU/lumbre/releases/tag/v1.4). Local packaging evidence
  is under `app/build/outputs/release-v1.4/`; these generated files are not versioned.
  Its signing certificate matches 1.3. Ordinary Gradle output remains unsigned.
- To distribute a release, use **Generate Signed App Bundle / APK** with your own key kept outside
  the repository. No signing keys are generated and no passwords are stored in the project.
- Install from Android Studio or with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Tap **Add EPUB**, pick a book and open its cover. Tap the centre to hide or show the controls;
  `Aa` opens the appearance panel and the table of contents changes chapter.

## Structure

```text
data/                 Room, DataStore, repository and private files
epub/                 models, parser, safe paths and rendering
ui/library/           library and global search
ui/reader/            ViewModel, WebView, controls and panels
ui/settings/          preferences
ui/theme/             Material 3 and dynamic color
app/schemas/          versioned Room schema
```

Manual dependency injection through `ReaderApplication`; ViewModels use StateFlow and coroutines.
Storage, security, EPUB engine details and trade-offs are documented in
[ARCHITECTURE.md](docs/ARCHITECTURE.md). Initial inspection and sequence in
[IMPLEMENTATION.md](docs/IMPLEMENTATION.md).

## Tests

The JVM suite covers the EPUB 2/3 parser, metadata, malformed files, limits, paths, XML entities,
sanitization, preferences, progress, speech sentence boundaries and language matching.
The Android suite checks the DAO, bookmark cascade, library, themes, reader behavior,
speech playback and fallback, sentence highlighting and player controls. The run log is recorded in
[VERIFICATION.md](docs/VERIFICATION.md).

`docs/samples/lumbre-demo.epub` is an original test book with two chapters, a cover, CSS, links
and enough text to exercise scrolling. It is regenerated with `scripts/New-TestEpub.ps1`.
It is never imported or displayed automatically in production. Verified screenshots are added to
`docs/screenshots/` whenever a device is available.

Screenshots from the Android 35 emulator: [library](docs/screenshots/02-library.png),
[reader](docs/screenshots/03-reader.png), [appearance](docs/screenshots/04-appearance.png),
[search](docs/screenshots/09-search.png), [landscape](docs/screenshots/12-landscape.png).

Lumbre 1.4 local release verification (19 October 2026): **46 JVM tests passing** and
**8 targeted speech regression tests passing on API 37**. Debug/release Lint reports
0 errors and 27 warnings per variant. The opt-in real-engine test was not run.
The [tag workflow](https://github.com/Hector-RU/lumbre/actions/runs/39319038583) completed,
but its API 37 job logged an APK installation failure despite a green status; it does not
establish a full API 37 test pass. See [VERIFICATION.md](docs/VERIFICATION.md) for the
separate local and CI evidence, historical 1.3 results and manual-validation limits.

Integration audit (23 October 2026): the full automatic suite passed **39 tests on API 26**.
The latest API 37 retry passed **37 of 39 tests**; annotation-action discovery and last-page
clamping failed. [PR #1](https://github.com/Hector-RU/lumbre/pull/1) records the results and
remains a draft pending resolution of those checks. See [verification](docs/VERIFICATION.md).

## License

Lumbre is distributed under the Apache License 2.0; see [LICENSE](LICENSE).

## Dependencies and licenses

Versions are centralized in `gradle/libs.versions.toml`. AndroidX, Compose, Material, Room,
DataStore, Kotlin, Coroutines and Coil: Apache-2.0. jsoup: MIT.
Notices and license texts are bundled in `app/src/main/assets/licenses/` and can be read in
Settings → Licenses. Only system fonts or fonts embedded in the EPUB are used.
Readium was evaluated as a possible engine evolution but is not added as a dependency.

## Limitations and next steps

- Scrolling, page and paragraph reading modes, persistent notes and configurable highlights.
- IDPF-obfuscated fonts are supported. DRM, Adobe legacy font obfuscation and fixed layout remain unsupported.
- New positions use a chapter/resource path and a UTF-16 text offset stable across reading layouts.
  Pagination opens the page containing the text. Older positions and image-only content fall back
  to a scroll fraction. This locator is internal, not EPUB CFI; it assumes the same book content.
- Search lists individual matches in the main reading order and opens their text position.
  Results are limited to 5,000 per query, with an explicit notice; refine the query to narrow them.
- Internal links and table-of-contents entries can open manifest XHTML outside the spine; Back returns
  to the previous reading location. External links remain blocked.
- Import limits: 512 MiB compressed, 1 GiB declared uncompressed, 20,000 entries;
  2 MiB XML and 8 MiB XHTML chapters. The book is never fully loaded into memory.
- Settings → Data exports/restores a versioned ZIP backup (up to 1,000 books, 2 GiB total and 8 MiB
  metadata). Restore adds missing books, keeps existing duplicates and applies the saved preferences.
  Backups are unencrypted; choose a suitable storage location. App-language selection is managed by
  Android separately and is not included. Interrupted restores roll back on restart; exports may leave
  a partial provider document if interrupted. Synchronization and statistics remain pending.
- Historical manual compatibility, accessibility and backup checks are documented in
  [VERIFICATION.md](docs/VERIFICATION.md). Signed-upgrade testing with populated data,
  physical-device TalkBack and audible voice quality were not revalidated for 1.4;
  additional device/EPUB coverage is tracked in `docs/ROADMAP.md`.

See [ROADMAP.md](docs/ROADMAP.md) for completed work, priorities and release gates.


## 1.4 validation

See [COMPATIBILITY.md](docs/COMPATIBILITY.md) for the device/publication acceptance matrix,
[VERIFICATION.md](docs/VERIFICATION.md) for actual results and [.github/workflows/android.yml](.github/workflows/android.yml)
for CI. TTS pauses when leaving the reader and requires an installed offline voice for the book
language. It is not a background playback service. Each book can belong to one named collection.

Signed release: `app/build/outputs/release-v1.4/lumbre-1.4.apk`. Its certificate matches 1.3;
Room v5 and backup v4 are unchanged. Packaging checksums and provenance sit beside the local APK.
Download the APK and checksum from the [1.4 release](https://github.com/Hector-RU/lumbre/releases/tag/v1.4).
