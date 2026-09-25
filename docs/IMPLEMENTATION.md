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

## Scope

First release: DRM-free reflowable EPUB, vertical chapter-based reading. Pagination, notes,
highlights, synchronization and export are left for later. A EPUB with scripts never runs them.
The initial fonts are system families, without redistributing font files.

## Delivery

MVP implemented on top of the original module. It also includes in-book search, bookmarks,
list view, sorting, book information and progress management.
Debug/release builds, JVM/Android tests and the acceptance walkthrough are recorded in
`VERIFICATION.md`. The compatibility and precision limitations documented in
`ARCHITECTURE.md` are kept; the release APK has no publishing signature.
