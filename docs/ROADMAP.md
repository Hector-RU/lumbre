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

## Following iterations

1. Support obfuscated fonts separately from DRM; retain explicit errors for unsupported encrypted books.
2. Notes and highlights built on persistent text ranges, including schema migration and backup evolution.
3. TTS with reading-position continuity and accessible playback controls.
4. Decide whether CFI interoperability, fixed-layout support or a broader EPUB engine is needed.
5. Optional synchronization and statistics after defining privacy, conflict handling and retention.

## Current limits

- Internal offsets target the same unmodified private EPUB; no CFI interoperability or edit relocation.
- Pagination shows the page containing the saved text, not necessarily that character at its top.
- Images or legacy records without a text locator use a fractional fallback.
- Search covers the main reading order and caps at 5,000 results per query.
- Back's full supplementary navigation stack lasts for the session; the current location persists.
- Backup is unencrypted and bounded to 1,000 books/2 GiB, 8 MiB metadata and 10,000 bookmarks per book.
- Existing duplicates keep their reading data on restore; backup preferences replace current preferences.
- Abrupt process death can interrupt restore after some books have been added; retries add missing books.
- Android's per-app language setting is not included in portable backup.
