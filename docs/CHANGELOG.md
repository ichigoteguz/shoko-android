# Changes

## 0.1.7 — Released (November 14, 2026)

- Publish the refresh, separate-subset and native AniDB submission workflow developed in 0.1.5, including the 0.1.6 date-format correction.
- Resolve the screen and selected bottom tab from the same destination, including fallback to Files.
- Reset bottom-bar visual interaction state on destination changes and show a visible active-tab indicator.
- Cover automatic recognition return, guarded navigation and Activity recreation with tab-selection assertions.

## 0.1.6 — Date format correction (November 14, 2026)

- Use AniDB's `dd.mm.yyyy` release-date format for native defaults, labels and form metadata.
- Convert valid ISO release dates in existing drafts to the correct format while preserving manual edits and blanks.

## 0.1.5 — Local validation (November 14, 2026)

- Refresh Files on return and foreground resume, clamp shrinking pagination, and reject outdated responses.
- Separate the AVDump pool from each anime's AniDB subset; keep pending recognition groups while preparing the next anime.
- Reset completed groups and return to refreshed Files when Shoko recognizes the full pool.
- Automatically open the native editor after AniDB authentication; add native Next and Add Files actions, editable default release dates, and explicit submission results.
- Preserve optional official-page review and block automatic retries of interrupted submissions.

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
