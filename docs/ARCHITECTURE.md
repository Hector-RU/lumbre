# Architecture and decisions

## Components

`MainActivity → ReaderApp (Navigation Compose) → AppViewModel / ReaderViewModel → repositories`.
`ReaderApplication` owns the dependencies, Room and the scope that flushes pending positions.
The UI observes StateFlow with lifecycle; the parser and the calculations are independent of Compose.

- Room v2: books and bookmarks with cascade delete and a unique SHA-256 index.
  An explicit, non-destructive v1 → v2 migration adds nullable text offsets and supplementary paths.
- DataStore: global and reading preferences; values are validated at the storage boundary.
- `files/books/<UUID>/book.epub`: persistent copy of the selected document; cover stored in a separate file.
- The archive is never fully extracted. The ZIP directory is read and resources are opened on demand.
- The book percentage is approximate: `(chapter + vertical fraction) / chapter count`.
- Position: chapter, optional supplementary resource path and UTF-16 text offset, plus a fallback
  scroll fraction. Saved at most once per second, on navigation,
  on exit and in `ON_STOP`. The serial queue batches pending writes and lives in the application
  scope so the last write is not lost when a ViewModel is cleared.
- Before layout transformation, sanitized text receives reader-owned offset markers. These survive
  both column pagination and paragraph cloning. DOM ranges capture visible text and locate it after
  a reload. A page opens at the column containing the saved character. Fractions cover legacy
  positions and image-only pages. Capture uses Chromium's visual viewport rather than assuming
  the whole layout viewport is visible, including at large system font scales. In-flight captures
  coalesce a final read after scrolling. Passive reflow retains the intended character instead of
  replacing it with the first character of its page. Offsets refer to the unchanged private EPUB;
  they are not CFI. SVG/MathML graphics retain their native node structure and do not receive text
  wrappers; graphic-only positions use the fractional fallback.
- Auxiliary XHTML navigation retains a session return stack; its current path and locator persist.
  After a process restart, Back from auxiliary content returns to the owning chapter.

## EPUB engine

[Readium Kotlin](https://github.com/readium/kotlin-toolkit) was evaluated: a maintained project,
[BSD-3-Clause](https://github.com/readium/kotlin-toolkit/blob/develop/LICENSE) licensed, with
broader publication and navigation support. It is a suitable alternative to expand compatibility,
especially CFI locators, DRM and pagination.

The app uses an isolated, bounded parser built on the Java ZIP APIs and jsoup (MIT), plus WebView
for XHTML/CSS, with scroll, page and paragraph modes. Reader-owned JavaScript measures columns
and text ranges; there is no local server. Resource and import limits are enforced. **It does not implement the whole EPUB standard.**
OPF, spine, EPUB 3 nav and EPUB 2 NCX are resolved without loading chapters into the model.
jsoup's XML parser does not resolve external entities. Images, CSS and unencrypted local fonts
are supported; files with `encryption.xml` (including obfuscated fonts) and fixed layout are
rejected explicitly.

## Security and privacy

- SAF (`OpenDocument`); no Internet or global storage permission.
- No persistent URI permission is retained because the document is copied while the temporary
  grant is valid. The URI only records the origin. Moving or deleting the original does not break the library.
- ZIP: 512 MiB compressed maximum, 1 GiB declared uncompressed, 20,000 entries, 128 MiB per resource.
- XML: 2 MiB maximum; XHTML: 8 MiB; cover: 16 MiB. Text reads are bounded and checked.
- Resources served to the WebView are also limited while inflating: 2 MiB for CSS,
  32 MiB for images/fonts, even when the ZIP directory declares a false size.
- Folders from interrupted imports are removed at startup under the same mutex as the import and
  only after the identifiers persisted in Room have been checked.
- Absolute paths, traversal, backslashes and ambiguous entries are rejected.
- WebView enables reader-owned JavaScript in every mode to measure text locators. Publication
  scripts and event attributes are stripped; CSP `script-src 'none'` blocks document scripts.
  No JavaScript bridge is installed. `file://` and `content://` access, DOM storage, popups
  and network loads are disabled.
- Every book gets its own exclusive virtual HTTPS origin. `shouldInterceptRequest` serves only
  resources declared in its manifest; anything else returns 403 and never falls through to the network.
- The CSP restricts scripts, frames, forms, objects and connections; active elements, event
  handlers, redirects and `<base>` are stripped from the XHTML. External links are never opened.
- Resource streams close their ZipFile. The WebView is destroyed when it leaves composition.
- No analytics, ads, accounts or telemetry. Backup and auto-transfer are disabled so private
  libraries are not copied and partially restored.
- Deletion affects only the database and the private copy, after confirmation.
- Manual backups are user-selected ZIPs, prepared privately before opening the destination.
  The versioned JSON manifest contains preferences, progress and bookmarks; EPUB entries are named
  by SHA-256. Restore checks entry names, duplicates, checksums, format, byte limits and EPUB
  validity before adding books. On a handled failure it removes newly added books. Existing
  duplicates retain their progress and bookmarks. Abrupt process death during restore can leave
  a partially restored library; a retry adds the remaining books. Backup ZIPs are not encrypted.
  Bounds: 1,000 books, 2 GiB compressed/inflated total, 8 MiB manifest and 10,000 bookmarks/book.
  Android app-language selection is not stored in the preferences backup.

## Dependencies

AGP 9.4.1, wrapper 9.6.0 and the AGP-bundled Kotlin 2.2.10 are kept. The Compose plugin matches
Kotlin; KSP 2.3.6 avoids the source-set API that broke earlier versions with AGP 9.
AndroidX/Compose/Room/DataStore cover UI, state and storage (Apache-2.0).
Coil 3.3.0 loads covers at a target size, without the network module (Apache-2.0).
jsoup 1.23.2 processes XML/HTML and sanitizes content (MIT).
Versions are centralized in the version catalog. No DI framework is added.

## Evolution

Future migrations must be explicit and tested against `app/schemas`; there are no automatic
destructive migrations. EPUB models do not depend on the UI so the engine can be replaced.
Text locators, pagination, supplementary XHTML links and portable backup are implemented. Search
returns individual matches with text offsets (up to 5,000/query) and highlights the selected chapter.
Next steps: broader EPUB/device validation, manual TalkBack review, obfuscated fonts, annotations
and optional CFI interoperability. Priorities and acceptance criteria are in `ROADMAP.md`.
