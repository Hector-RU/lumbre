# Architecture and decisions

## Components

`MainActivity → ReaderApp (Navigation Compose) → AppViewModel / ReaderViewModel → repositories`.
`ReaderApplication` owns the dependencies, Room and the scope that flushes pending positions.
The UI observes StateFlow with lifecycle; the parser and the calculations are independent of Compose.

- Room v1: books and bookmarks with cascade delete and a unique SHA-256 index.
- DataStore: global and reading preferences; values are validated at the storage boundary.
- `files/books/<UUID>/book.epub`: persistent copy of the selected document; cover stored in a separate file.
- The archive is never fully extracted. The ZIP directory is read and resources are opened on demand.
- The book percentage is approximate: `(chapter + vertical fraction) / chapter count`.
- Position: chapter plus vertical scroll fraction. Saved at most once per second, on navigation,
  on exit and in `ON_STOP`. The serial queue batches pending writes and lives in the application
  scope so the last write is not lost when a ViewModel is cleared.
- The fraction keeps the place while the geometry is unchanged. Changing font, margins or screen
  approximates the position; a text locator/CFI is pending for cross-layout precision.

## EPUB engine

[Readium Kotlin](https://github.com/readium/kotlin-toolkit) was evaluated: a maintained project,
[BSD-3-Clause](https://github.com/readium/kotlin-toolkit/blob/develop/LICENSE) licensed, with
broader publication and navigation support. It is a suitable alternative to expand compatibility,
especially CFI locators, DRM and pagination.

This MVP uses an isolated, bounded parser built on the Java ZIP APIs and jsoup (MIT), plus WebView
for XHTML/CSS. The decision keeps a vertical reading engine with no JavaScript and no local server,
with verifiable limits. **It does not implement the whole EPUB standard.**
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
- WebView: JavaScript, `file://` and `content://` access, DOM storage and network loads are disabled.
- Every book gets its own exclusive virtual HTTPS origin. `shouldInterceptRequest` serves only
  resources declared in its manifest; anything else returns 403 and never falls through to the network.
- The CSP restricts scripts, frames, forms, objects and connections; active elements, event
  handlers, redirects and `<base>` are stripped from the XHTML. External links are never opened.
- Resource streams close their ZipFile. The WebView is destroyed when it leaves composition.
- No analytics, ads, accounts or telemetry. Backup and auto-transfer are disabled so private
  libraries are not copied and partially restored.
- Deletion affects only the database and the private copy, after confirmation.

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
Next steps: CFI/text locator, pagination, links to content outside the spine, obfuscated fonts,
annotations and portable backup. The current search returns one entry per chapter and the WebView
highlights matches in the selected chapter.
