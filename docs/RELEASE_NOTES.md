# Lumbre release notes

## 1.2 — 15 October 2026

- Reading progress and bookmarks retain a text location across typography and reading-mode changes.
  Existing libraries migrate from Room v1 to v2 without deleting books or bookmarks.
- In-book search lists individual occurrences and opens the selected match. Queries are capped at
  5,000 results, with an explicit notice when that limit is reached.
- Internal links and contents can open supplementary XHTML outside the main reading order;
  Back returns to the previous location within the reading session.
- Settings → Data exports/restores a portable ZIP containing books, progress, bookmarks and app/reading
  preferences. Restore adds missing books, preserves existing duplicates and applies saved preferences.
- Accessible page actions and radio-choice semantics improve navigation. Large-text regression tests
  cover Chromium's visual viewport at 200% font scale. SVG text retains its original structure.

The app continues to work offline without accounts, analytics or Internet/global storage permissions.
Backups are unencrypted, limited to 1,000 books/2 GiB and exclude Android's app-language setting.
Text locators refer to the same unchanged EPUB and are not EPUB CFI; graphic-only and older positions
use a fractional fallback. Notes, highlights, TTS, synchronization, DRM and fixed-layout support remain
outside this release. See `VERIFICATION.md` for automated results and user-reported manual acceptance.

Android: versionName `1.2`, versionCode `3`, minSdk `26`, targetSdk `37`.
