# Lumbre release notes

## 1.3 — release candidate (not published)

- Reflowable EPUBs may use standard IDPF-obfuscated publisher fonts. Resources are decoded on
  demand; DRM and unknown obfuscation algorithms remain unsupported.
- Long press text in any reading mode and choose **Highlight and annotate** to save a passage
  with an optional note. **Highlights and notes** in the reader menu opens, edits and deletes them.
- Choose shading, underline or both, with five colors and a live preview in the annotation editor.
  Annotation cards distinguish passages and notes and can expand long text.
- Room v5 retains existing libraries and stores text ranges, quotes, notes, styles and colors.
- Portable backup v4 includes collections and annotation styles/colors and restores v1/v2/v3 backups. Range/quote validation
  happens before library changes; duplicate books retain their current annotations.
- Search highlights the chosen occurrence with a reader-owned mark, avoiding native-find focus jumps.
- Older marks retain their combined yellow style. Selections and notes each support up to 10,000 UTF-16 units.
  Offsets refer to the same unmodified EPUB. This iteration is not included in the signed 1.2 APK.

- Library collections and combined unread/reading/finished filters organize books.
- Notes/highlights export to a user-selected Markdown document.
- Offline installed TTS voices provide foreground playback, pause, speed and chapter continuity.
- Backup restores recover from process interruption, support cancellation and display book progress.
- Repeated search text is cached within a strict process-memory budget; overlapping marks use a sweep.
- Reader layout readiness checks fonts/images with a bounded Chromium settling fallback.
- Older Android versions retain the reading viewport when hiding system bars; legacy WebViews use correct visual/layout coordinate conversion for saved positions.
- Automated CI configuration covers JVM, Lint, builds and release/manual API 26/37 device jobs.

Android: versionName `1.3`, versionCode `4`. Room v5 and backup v4 migrations are non-destructive.
The candidate is not signed/published by this verification. Physical-device, TalkBack and broad
publisher EPUB acceptance remain explicit delivery gates.

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
