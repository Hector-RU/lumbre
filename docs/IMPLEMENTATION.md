# Implementation plan

## Initial inspection (24 September 2026)

- Single `app` module; namespace and applicationId `com.hector.epubreader`.
- minSdk 26, compileSdk/targetSdk 37; AGP 9.4.1, Gradle 9.6.0.
- Kotlin bundled with AGP: 2.2.10. Java bytecode 11; Android Studio daemon JDK 25.
- Version catalog `gradle/libs.versions.toml`. AppCompat, Material Views and Core already present.
- No Activity, no Compose screens and no application logic. No AGENTS.md and no Git repository yet.

## Sequence

1. Verify the original build; keep SDK, AGP and wrapper.
2. Compose activity, Material 3 and Library / Search / Settings navigation.
3. Room for library and bookmarks; DataStore for preferences; repositories and manual injection.
4. SAF import into private storage with resource limits, validation and deduplication.
5. Isolated EPUB 2/3 parser: container, OPF, manifest, spine, nav/NCX, covers and relative resources.
6. WebView without JavaScript or file/network access; resources served from an intercepted virtual HTTPS origin.
7. Preferences, table of contents, search, bookmarks and persistent progress with batched writes.
8. Security/parser/progress tests, lint, debug/release and execution when a device is available.

## Initial scope (historical)

First release: DRM-free reflowable EPUB, vertical chapter-based reading. Pagination, notes,
highlights, synchronization and export are left for later. A EPUB with scripts never runs them.
The initial fonts are system families, without redistributing font files.

## Delivery

MVP implemented on top of the original module. It also includes in-book search, bookmarks,
list view, sorting, book information and progress management.
Debug/release builds, JVM/Android tests and the acceptance walkthrough are recorded in
`VERIFICATION.md`. The compatibility and precision limitations documented in
`ARCHITECTURE.md` are kept; the initial release APK had no publishing signature. A signed 1.1 artifact was verified
on 3 October; ordinary Gradle output remains unsigned.

## 15 October 2026 iteration

- Room v2 migration preserves existing books/bookmarks and adds text offsets/resource paths.
- Reader text locators and accessible forward/backward page actions work across the three modes.
- Search returns separate matches with exact targets (bounded at 5,000 with a UI notice).
- Internal links and contents can open manifest XHTML outside the linear reading order.
- Settings includes manual ZIP backup/restore of private books, positions, bookmarks and preferences.
- Security documentation distinguishes reader-owned JavaScript from blocked publication code.
- Migration, text-index, backup, supplementary-link, script-blocking and rendering tests extend coverage.

The live backlog is in `ROADMAP.md`; observed test results belong in `VERIFICATION.md`.

## Lumbre 1.2 release preparation (15 October 2026)

Android versionName is `1.2` and versionCode is `3`. The signed APK and checksum are under
`app/build/outputs/release-v1.2/`, separate from ordinary unsigned Gradle output. The certificate
matches the verified 1.1 distribution. Release notes are in `RELEASE_NOTES.md`; observed checks
and manual acceptance are recorded in `VERIFICATION.md`. Signing material remains outside Git.
