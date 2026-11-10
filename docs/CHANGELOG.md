# Changes

## 0.1.4 — Stable (November 10, 2026)

- Add an About dialog with the app version and "Developed with OpenAI Codex" credit, available through the top bar's info button.
- Add the development credit to the README and publish the repository publicly at the owner's request.

## 0.1.3 — Stable (November 10, 2026)

- Owner confirmed the updated app functions as expected; published as the first stable personal release.
- Fix a launch crash in automatic anime-title suggestions: Android's ICU pattern parser requires escaped delimiters that the desktop JVM accepted without escaping.
- Keep saved batches accessible through manual search even if optional filename parsing fails.
- Add direct Android parser tests and verify the complete dump-to-search flow on the connected phone.
- Verify two cold starts of the signed release with the user's existing saved batch and automatic results.

## 0.1.2 (withdrawn: Android launch crash)

- Prepopulate anime search after AVDump using the selected release filenames, removing group tags, episode numbers and technical suffixes while preserving meaningful title numbers and seasons.
- Search automatically after a short pause and show the results without requiring Find anime.
- Keep manually edited or cleared queries when returning to the screen or restoring a draft.
- Cancel outdated requests and clear results when changing the selection, batch or server.

## 0.1.1

- Recognize AniDB's CSS-hidden anime-title and episode-catalog helper controls without exposing or changing them in the native editor.
- Continue rejecting new visible fields and other unknown controls so new options are not silently dropped.
- Add field-name/type diagnostics for form compatibility failures; logs contain no field values, credentials or hidden tokens.
- Add browser-fixture regressions for hidden metadata preservation and visible-field rejection.

## 0.1.0

- Initial native Shoko unrecognized-file and AVDump workflow.
- Native AniDB Mass Add editor with advanced tracks, templates, drafts and per-file review.
- User-operated official AniDB review/submission and Shoko recognition checks.
- Shoko Gray theme and bundled Sora typography.
