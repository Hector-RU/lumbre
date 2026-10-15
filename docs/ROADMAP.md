# Lumbre roadmap

Updated: 15 October 2026 (America/Santo_Domingo).

## Implemented in this iteration

- Persistent internal text locators for reading progress and bookmarks, with a non-destructive Room v2 migration.
- Search results per occurrence, each with its own locator; 5,000-result limit explicitly shown.
- Manifest XHTML navigation outside the spine, including contents entries and session Back navigation.
- Manual SAF ZIP backup/restore: books, positions, bookmarks and app/reading preferences.
- Accessible forward/backward reading actions and automated regression coverage across reading modes.

## Next release gates

1. Re-run the verification suite for the selected release. This iteration already passed 28 JVM and
   19 Android tests; the environment and scope are recorded in `VERIFICATION.md`.
2. Test a legally available, varied EPUB corpus: EPUB 2/NCX and EPUB 3/nav, nested sections, footnotes,
   tables, images, embedded fonts, long chapters and unusual publisher CSS. Record each fixture and result.
3. Run on API 26 and at least one recent physical device, with different Android System WebView versions.
4. Manually review TalkBack reading order, focus after navigation and sheets, page actions, control
   visibility, large system fonts, portrait/landscape and cutouts. Automated actions do not certify TalkBack.
5. Exercise SAF export/restore using local and cloud document providers, low storage, malformed backup,
   cancellation and interruption. Restoring keeps duplicate books and applies backed-up preferences.
6. Review Lint warnings, select the release version, sign with the owner's existing key and verify the
   resulting distribution. Do not confuse ordinary unsigned Gradle output with historical signed APKs.

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
