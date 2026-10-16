# Compatibility and release acceptance

Each release must attach actual results, not infer device coverage from unit tests.

| Environment | Required checks | Status for 1.3 |
| --- | --- | --- |
| API 26 AOSP emulator, Chromium 58 | complete instrumentation suite, including backup and test TTS engine | 33 passed; stock AOSP WebView registered with a resource overlay |
| API 37 emulator, Chromium 149 | complete instrumentation suite | final local results recorded in VERIFICATION.md |
| Physical device, recent Android | TalkBack, 200% font, rotation, selection toolbar, TTS voice | pending device availability |
| Local and cloud SAF providers | export, restore, cancellation, revoked permission | pending manual provider validation |
| Low storage | interrupted writes, recovery and useful error | injected stream failure regression; physical validation pending |

## Publication corpus

Keep an explicit list of legally usable EPUBs and their SHA-256, publisher, language,
layout, embedded fonts, chapter count, size, Android API and WebView version. Do not
commit copyrighted publications without permission. Original synthetic fixtures cover
EPUB 2/3 navigation, nested markup, Unicode, font obfuscation, SVG, hostile content
and overlapping annotations. They do not replace real publisher compatibility testing.

For each publication, test all three reading modes, repeated typography changes,
search occurrences, restart, overlapping annotations and backup round-trip. Include
right-to-left text, CJK, footnotes, tables, very long chapters and image-only sections.

## Upgrade acceptance

Install signed 1.2, import and annotate where supported, save progress and bookmarks,
then update with a signed 1.3 using the same certificate. Check saved data, export and
restore. Room migrations from schemas 1–4 to schema 5 are tested independently from this signed APK upgrade.
A debug build cannot prove certificate continuity with a signed release.


## Local publisher corpus used for 1.3

The following EPUB3 downloads stay in ignored `test-artifacts/corpus/`; they are not bundled
or redistributed with the app. `PublisherCorpusTest` parses every reading-order chapter and
checks original UTF-16 marker text in scroll, pages and paragraphs. This is automated parser/
HTML verification, not physical-device visual acceptance. In CI without supplied EPUBs, the
same test uses the original `docs/samples/lumbre-demo.epub`; no test is skipped.

| File / source | Chapters | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| [alice.epub](https://www.gutenberg.org/ebooks/11.epub3.images) | 15 | 189249 | `4f81245d31380ac1d6eafb9a321e3691369bc70985736ae4bccf849a4f784036` |
| [frankenstein.epub](https://www.gutenberg.org/ebooks/84.epub3.images) | 32 | 474733 | `b82b1ad5cdd15439ab99e864741a0343f107adc796ce4326e44235a3f6f558a7` |
| [don-quijote.epub](https://www.gutenberg.org/ebooks/2000.epub3.images) | 15 | 920553 | `8ee8c3650d397da45097214f21cff57d2ce8d035dfcd6585e0096e3bb74989fa` |

## Reproducing local API 26 coverage on Windows

1. Run `python scripts/Download-CompatibilityImage.py` (Python 3.9+).
2. Start `scripts/Start-CompatibilityEmulator.ps1`, using its isolated `LumbreApi26` AVD.
3. The AOSP API 26 r1 image includes WebView 58 but lists Google provider packages in its
   framework configuration. `scripts/Enable-CompatibilityWebView.ps1` registers the bundled
   `com.android.webview` with a static resource overlay and restores its SELinux labels, then
   reboots this disposable AVD. It does not modify a WebView binary or the production app.
   Run this setup again if a fresh writable-system copy is created. Security enforcement stays enabled.
4. Run `scripts/Verify-Project.ps1 -Serial emulator-5582` after boot/unlock.
5. Signed-upgrade verification uses `scripts/Test-SignedUpgrade.ps1` on this AVD only. It builds
   a standalone Java probe and checks the book, text position and bookmark across signed 1.2→1.3.
   Signing passwords are read from process environment variables; keep credentials outside Git.

For normal CI emulators, the Google APIs images already provide their configured WebView;
this AOSP-only setup is unnecessary. CI retains animations because motion regressions verify them.
