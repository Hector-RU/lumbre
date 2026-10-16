# Compatibility and release acceptance

Each release must attach actual results, not infer device coverage from unit tests.

| Environment | Required checks | Status for 1.3 |
| --- | --- | --- |
| API 26 emulator, default WebView | import, resume, annotations, backup and TTS | CI matrix configured; pending run |
| Recent emulator | complete instrumentation suite | local verification recorded in VERIFICATION.md |
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
