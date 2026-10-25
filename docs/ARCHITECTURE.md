# Architecture and decisions

## Components

`MainActivity → ReaderApp (Navigation Compose) → AppViewModel / ReaderViewModel → repositories`.
`ReaderApplication` owns the dependencies, Room and the scope that flushes pending positions.
The UI observes StateFlow with lifecycle; the parser and the calculations are independent of Compose.

- Room v5: books, bookmarks and annotations with cascade delete and a unique book SHA-256 index.
  Explicit, non-destructive v1 → v2 → v3 → v4 → v5 migrations retain prior data, add annotations and preserve old marks
  with explicit combined/yellow defaults for the new style/color columns.
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
jsoup's XML parser does not resolve external entities. Images, CSS, plain local fonts and
IDPF-obfuscated fonts are supported. The latter follow [EPUB 3.3 section 4.4](https://www.w3.org/TR/epub-33/#sec-font-obfuscation):
remove XML whitespace from the package's referenced unique identifier, derive its UTF-8 SHA-1,
and XOR the first 1040 font bytes. Only font resources declared in the manifest are accepted;
the bounded resource stream decodes them in memory without extracting fonts. Other algorithms,
including DRM and Adobe legacy obfuscation, and fixed-layout publications remain unsupported.

## Highlights and notes (included in 1.3)

Annotations store a book foreign key, resource path, owning chapter, half-open UTF-16 range,
original quote, optional plain-text note, creation time, style ID and color ID. Styles and colors
are whitelisted enums; publication/backup strings never become arbitrary CSS. Repository and backup boundaries
validate the range against the canonical sanitized text. Native WebView selection gains a
reader-owned action via ActionMode; evaluateJavascript returns only range boundaries, with no
JavaScript bridge. While selection is active, the reader also exposes the same annotation
action on its accessibility node; the action is removed when selection ends. Native selection
remains enabled in scroll, pages and paragraph modes.
The editor previews shading, underline or both with five named colors and an optional note.
Radio semantics/check marks expose selection independently of color. The list separates the
passage and note into cards, labels chapter/style/color, expands long text and supports navigation,
editing and deletion. Errors retain the editor for retry.

Before layout, highlighted ranges split existing offset markers while preserving each
marker's text node and absolute offset. Inline shading and colored underlines support overlapping ranges and older WebViews.
A shaded range uses a fixed accessible background/ink pair; an underline preserves the reading
text color and uses a theme-appropriate line color. Overlapping shading and underline keep their
independent colors; the last covering mark of each kind wins when multiple colors overlap. Adding/removing a range reloads the resource
using the current text locator; editing only a note does not alter the render key. Notes are
rendered by Compose as text and never injected into publication HTML.

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
  The versioned JSON manifest contains preferences, progress, bookmarks and annotations; EPUB entries are named
  by SHA-256. Restore checks entry names, duplicates, checksums, format, byte limits and EPUB
  validity before adding books. On a handled failure it removes newly added books. Existing
  duplicates retain their progress, bookmarks and annotations. Abrupt process death during restore leaves a journal; next startup rolls back
  newly imported books and restores previous preferences. Backup ZIPs are not encrypted.
  Bounds: 1,000 books, 2 GiB compressed/inflated total, 8 MiB manifest and 10,000 bookmarks and 10,000 annotations/book. Backup v4 restores v1, v2 and v3 as well; v2 annotations receive the original combined/yellow appearance.
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
returns individual matches with text offsets (up to 5,000/query). The selected occurrence is
rendered as a temporary reader-owned yellow shade, alongside persistent annotations. Native find
is not used because it can pan back to its first match after locator restoration. A visual-state
callback commits the restored viewport before the reader reports readiness. Search marks are not
saved in Room or backups and disappear on subsequent navigation/reload.
Next steps: broader EPUB/device validation, manual TalkBack review of selection/annotations
and optional CFI interoperability. Priorities and acceptance criteria are in `ROADMAP.md`.


## 1.3 data and reader evolution

Room v5 adds a non-null empty-default collection column with a 4→5 migration. Collection updates
modify only that column so a concurrent reading-progress write is preserved. Backup v4 restores
v1–v4; older versions default to no collection. Restore persists all planned book UUIDs and old
preferences in an AtomicFile journal before mutating Room. Successful completion removes it;
exceptions/cancellation or next startup remove only those books and restore previous preferences.
The journal is retained if recovery fails. Unfinished cache staging is pruned before new operations.
The UI blocks library mutations during startup recovery and backup operations. Export prepares a
complete ZIP in cache before writing SAF; provider output cannot be assumed atomic.

Canonical text uses a per-repository 8 MiB/128-entry process LRU keyed by EPUB checksum/resource.
Large chapters bypass it. Annotation rendering traverses text nodes in offset order with an active
range sweep, retaining original precedence for overlapping colors/styles. The WebView implementation,
Compose adapter and pagination JavaScript are separate files. Readiness checks local font/image
completion but still permits 800 ms for Chromium scroll metrics, bounded by a 3 s fallback.

ReaderSpeech binds Android TTS and selects installed, non-network voices matching publication
language. It uses canonical UTF-16 chunks, progress callbacks and audio focus. Playback pauses on
lifecycle stop and navigation, and the engine shuts down with its ViewModel. Engines without range
callbacks resume from the current chunk. No background service or extra permission is introduced.
The instrumented test APK contains a silent test engine; production does not package it.


Legacy WebView coordinate compatibility is explicit: before Chromium 61, `window.scrollX/Y`
refer to the visual viewport while DOM rectangles use the layout viewport. Capture derives
visual bounds from native WebView dimensions/scroll plus the root DOM rectangle; restoration
and resize anchors use the layout scroll derived from that rectangle. Modern WebViews keep
the visualViewport path. See [Chromium's viewport explanation](https://developer.chrome.com/blog/visual-viewport-api).
On Android <30, reader insets retain the observed bar sizes across immersive changes and reset
on orientation/density changes; this prevents controls from resizing the reading viewport.
Pagination waits for a visual commit both before measuring columns and before restoring native
scroll. Explicit turns invalidate older resize callbacks. TTS binds only after the reader opens
its voice controls; ordinary book opening does not start a speech engine.

## 1.4 read-aloud evolution

The expanded and compact players expose sentence/chapter navigation, narration seeking,
offline voice selection, playback speed and a sleep timer. Sentence shading uses
reader-owned rendering without altering publisher markup. Narration retains its text
position when paused; stale viewport captures must not overwrite that position.
Language matching accepts ISO-639-2 and underscore-separated tags, and unavailable
voices fall back to other installed offline voices. Playback remains foreground-only.
Room v5 and portable backup v4 are unchanged. Automated speech tests use the silent
test engine; installed-engine completion requires the opt-in `DeviceSpeechTest`.
