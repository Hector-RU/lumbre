# Lumbre

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Android EPUB reader written in Kotlin and Jetpack Compose. It provides a private library,
offline reading and a Material 3 interface in Spanish, built on top of the existing
Android Studio project.

## Features

- Import through the Android Storage Access Framework (SAF), with validation and duplicate detection.
- Persistent local copy: the book stays available even if the original file is moved.
- Covers, metadata, grid/list layout, search by title/author and five sorting criteria.
- Reflowable EPUB 2/3: chapters, nav/NCX table of contents, images, CSS, internal links and local fonts.
- Immersive reader with a central tap to show or hide controls, table of contents, search and bookmarks.
- Automatic chapter and position saving; progress resumes when the book is reopened.
- Four font families (including the book's own), size, line spacing, margins and justification.
- Six reading themes independent of the app light/dark/system theme and Material You.
- Book information, mark as read, restart progress and delete only the library copy.
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
- Release: `app/build/outputs/apk/release/app-release-unsigned.apk`.
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
sanitization, preferences and progress. The Android suite checks the DAO, bookmark cascade,
empty library and theme selection. The run log is recorded in
[VERIFICATION.md](docs/VERIFICATION.md).

`docs/samples/lumbre-demo.epub` is an original test book with two chapters, a cover, CSS, links
and enough text to exercise scrolling. It is regenerated with `scripts/New-TestEpub.ps1`.
It is never imported or displayed automatically in production. Verified screenshots are added to
`docs/screenshots/` whenever a device is available.

Screenshots from the Android 35 emulator: [library](docs/screenshots/02-library.png),
[reader](docs/screenshots/03-reader.png), [appearance](docs/screenshots/04-appearance.png),
[search](docs/screenshots/09-search.png), [landscape](docs/screenshots/12-landscape.png).

Verification performed: **19 JVM tests + 5 Android tests passing**; Lint reports no errors.
SAF import, reading, appearance controls, table of contents, force close, restoration, search,
bookmarks and deletion without removing the original EPUB were also checked manually.

## License

Lumbre is distributed under the Apache License 2.0; see [LICENSE](LICENSE).

## Dependencies and licenses

Versions are centralized in `gradle/libs.versions.toml`. AndroidX, Compose, Material, Room,
DataStore, Kotlin, Coroutines and Coil: Apache-2.0. jsoup: MIT.
Notices and license texts are bundled in `app/src/main/assets/licenses/` and can be read in
Settings → Licenses. Only system fonts or fonts embedded in the EPUB are used.
Readium was evaluated as a possible engine evolution but is not added as a dependency.

## Limitations and next steps

- Vertical, chapter-based reading. Pagination, notes, highlights and TTS are still pending.
- Encrypted/DRM EPUBs, obfuscated fonts and fixed layout are rejected with an explicit message.
- Position is stored as a vertical fraction: exact when returning with the same layout and
  approximate when typography or dimensions change. A text locator/CFI is pending for continuity.
- Search: one result per chapter; matches are highlighted when the chapter is opened.
- External links are not opened. Links to XHTML outside the spine are pending.
- Import limits: 512 MiB compressed, 1 GiB declared uncompressed, 20,000 entries;
  2 MiB XML and 8 MiB XHTML chapters. The book is never fully loaded into memory.
- Export, backup, synchronization and statistics are not implemented.
- Before publishing: validate against a varied set of real EPUBs and devices, check TalkBack
  accessibility and sign a release with the owner's key.
