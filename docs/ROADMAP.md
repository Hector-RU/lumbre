# Lumbre roadmap

Updated: 15 October 2026 (America/Santo_Domingo).

## Included in Lumbre 1.2

- Persistent internal text locators for reading progress and bookmarks, with a non-destructive Room v2 migration.
- Search results per occurrence, each with its own locator; 5,000-result limit explicitly shown.
- Manifest XHTML navigation outside the spine, including contents entries and session Back navigation.
- Manual SAF ZIP backup/restore: books, positions, bookmarks and app/reading preferences.
- Accessible forward/backward reading actions and automated regression coverage across reading modes.

## Manual acceptance

The user confirmed that the proposed compatibility, accessibility and backup tests were completed
and that everything worked correctly, with no reported issues. This acceptance is recorded in
`VERIFICATION.md`. Specific books, devices, versions and providers were not supplied.

## Lumbre 1.2 delivery

- Android versionName `1.2`, versionCode `3`; changes are summarized in `RELEASE_NOTES.md`.
- Signed APK: `app/build/outputs/release-v1.2/lumbre-1.2.apk`.
- The certificate matches 1.1, APK signature schemes v2/v3 verify, and 16 KiB ZIP alignment verifies.
- The APK's SHA-256 and release metadata are saved alongside the distribution.
- Release verification passed: 28 JVM tests, 19 Android tests, and Lint with 0 errors/20 warnings
  per variant. The user's manual acceptance and the controlled automated environment are recorded
  in `VERIFICATION.md`.

## Additional compatibility coverage to record

As more test details become available, record EPUB fixtures, API 26/recent physical devices,
Android System WebView versions, TalkBack behavior and local/cloud SAF providers. Edge cases to
cover include unusual publisher CSS, large books, low storage, malformed backups and interruption.
The user's successful manual acceptance above remains recorded.

## Next iteration implemented (unreleased)

- IDPF font obfuscation is decoded on demand, limited to manifest font resources. Unknown
  encryption algorithms, including DRM and Adobe legacy obfuscation, remain unsupported.
- Persistent highlights and optional notes use canonical UTF-16 start/end offsets and the
  original quote. Long press text, choose **Highlight and annotate**, then save. The reader's
  **Highlights and notes** menu supports navigation, note editing and deletion.
- Room v5 stores annotations, style and color without altering existing books, bookmarks or progress; deleting
  a book cascades to its annotations.
- Backup v4 includes collections and annotation styles/colors; restore accepts v1, v2, v3 and v4 and validates text ranges/quotes
  before changing the library. Existing duplicate books keep their current annotations.
- Choose shading, underline or both in red, yellow, green, blue or purple, with a live preview.
  Older marks retain their combined yellow style; newer marks initially use yellow shading.
  The annotation list separates the quote and note, labels the chapter/style/color and expands
  long content. Selections and notes are limited to 10,000
  UTF-16 code units each; graphic-only SVG/MathML text is outside the canonical text index.
- Pending delivery: manual acceptance on representative books/devices, final signed candidate
  verification and publication. This does not change the already published 1.2 APK.

## Following iterations

1. Extend the corpus/device matrix for obfuscated fonts, text selection and annotations; consider Adobe legacy font obfuscation if needed.
2. Validate foreground TTS voices/quality on physical devices; consider background playback if needed.
3. Decide whether CFI interoperability, fixed-layout support or a broader EPUB engine is needed.
4. Optional synchronization and statistics after defining privacy, conflict handling and retention.

## Current limits

- Internal offsets target the same unmodified private EPUB; no CFI interoperability or edit relocation.
- Pagination shows the page containing the saved text, not necessarily that character at its top.
- Images or legacy records without a text locator use a fractional fallback.
- Search covers the main reading order and caps at 5,000 results per query.
- Back's full supplementary navigation stack lasts for the session; the current location persists.
- Backup is unencrypted and bounded to 1,000 books/2 GiB, 8 MiB metadata and 10,000 bookmarks and 10,000 annotations per book; notes and selected text each have a 10,000-character limit.
- Existing duplicates keep their reading data on restore; backup preferences replace current preferences.
- Interrupted restore rolls back at next startup. An interrupted export can leave a partial document at its SAF destination.
- Android's per-app language setting is not included in portable backup.


## 1.3 implementation follow-through

- VersionName 1.3 / versionCode 4 prepares the next release. Published 1.2 remains historical.
- Recoverable backup restore journals planned IDs and previous preferences before importing.
  Restart rolls back an incomplete restore; cancellation does the same immediately. Orphan
  staging is removed, destination write failures preserve the library, and UI progress and
  cancellation are available. A provider can leave a partial destination after interrupted
  export; only a successful export is a usable backup.
- Room v5 adds one optional collection per book. Reading-state and collection filters compose;
  backup v4 carries collections and accepts versions 1–4. Duplicate books retain current data.
- Repeated searches use a process-local LRU with at most 8 MiB UTF-16 text and 128 entries.
  Annotation rendering sweeps ranges in text order while preserving overlapping mark precedence.
- Reader WebView, Compose integration and pagination scripts are separated. Layout readiness
  checks fonts/images and retains an 800 ms Chromium settling floor plus a 3 s fallback.
- Notes/highlights export as readable Markdown through SAF, with literal user/publication text.
- Foreground TTS reads from the current text offset, offers pause and speed, continues chapters,
  saves progress and pauses on screen exit or audio-focus loss. Only installed offline voices
  matching the book language are eligible. Short chunks bound replay on engines without word
  callbacks. Android engine/voice availability and audible quality still need physical testing.
- GitHub Actions runs JVM/Lint/build on changes, with API 26/37 instrumentation on tags/manual
  dispatch. Configuration is prepared locally; remote execution requires pushing the workflow.

Remaining delivery gates: physical-device/TalkBack and real-publication acceptance, API 26
matrix result, existing-key signing/certificate verification and publication. See COMPATIBILITY.md.
Synchronization and wider EPUB engine/CFI support remain separate future product decisions.
