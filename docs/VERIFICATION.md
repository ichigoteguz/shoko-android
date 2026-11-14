# Verification record

This record distinguishes local fixture verification from real services. Initial phone checks were executed on November 8, 2026; anime-search update checks were executed on November 10, 2026.

## Workflow update released as 0.1.7 — November 14, 2026

- Debug app assembly, **70 JVM tests**, Android lint (zero errors; 22 warnings), and Android instrumentation APK assembly passed.
- **22 JavaScript adapter tests passed**, including explicit Next/Add Files activation, asynchronous same-document review, retained episode/version choices, duplicate-action prevention, preflight validation, explicit result messages, and repeated identical validation errors.
- New JVM coverage checks separate subsets, mixed-anime pools, legacy workspace recovery, pending groups, completed/missing recognition states, full reset, editable date defaults, and submission retry gating after ambiguous outcomes.
- **8 Android instrumentation tests passed** on the connected Samsung Galaxy S24 Ultra (SM-S928U, Android 17/API 37). Smoke coverage includes automatic Files refresh, recognized-pool reset, subset selection, dump/search recovery and Activity recreation. The actual WebView fixture test covers explicit native Next → per-file review → Add Files → success, retained detected episode/version values, edited dates, and duplicate-action prevention.
- The device run exposed an outdated saved-draft test fixture containing placeholder ED2K text. It now uses a valid matching link and verifies subset recovery without changing the saved draft; a separate Android regression verifies malformed links cannot select the whole pool.
- The final debug APK is `app/build/outputs/apk/debug/app-debug.apk` (isolated `dev.shoko.remote.debug` test app). Device tests installed and exercised only the isolated test app with mock Shoko responses and a local AniDB fixture. No production-app update, publishing, real AVDump, or real AniDB submission was performed.
- Current live AniDB control handlers, release-date format, and result markup could not be independently audited: the internal browser remained on Cloudflare verification. The adapter retains observed control IDs and the existing `yyyy-mm-dd` date convention; explicit result parsing has fixture validation only. A supported live contribution and subsequent real Shoko recognition remain required acceptance checks.
- Native Add Files persists submission intent before activation. Interrupted/ambiguous outcomes cannot become retryable merely because a previous validation message remains on the page; only a new confirmed rejection unlocks retry. Automatic refreshes and restores never submit files.

### Signed 0.1.5 device update

- Built the local `0.1.5` release (version code `6`, package `dev.shoko.remote`); release assembly and vital lint passed. The APK is not debuggable.
- Verified APK Signature Scheme v2 and matched the public signing certificate to the installed `0.1.3` APK before updating.
- Installed with `adb install -r` on the owner's SM-S928U and verified installed version `0.1.5`, unchanged app UID/data directory and original first-install timestamp. The updated app cold-launched successfully and remained running with no startup AndroidRuntime/ShokoAniDb errors observed.
- Local installable artifact: `artifacts/release/Shoko-Remote-0.1.5.apk`; SHA-256 `570925E0DAD3BF41FC069861B27B455D748342816066F2EE5B4BB2E1F841E83D`.
- This is an installed local validation build, not a published or owner-confirmed stable release. Live submission is left to the owner.

### Signed 0.1.6 date-format correction

- The owner confirmed AniDB requires `dd.mm.yyyy`; 0.1.5's ISO convention was incorrect. Corrected the per-file and bulk-date defaults, visible labels, adapter metadata and tests.
- Added conversion of complete, valid ISO release dates in existing drafts/review baselines to dotted dates. Dotted dates, blank fields, partial edits and Shoko timestamps are preserved.
- **74 JVM tests, 23 JavaScript adapter tests and 8 physical-device instrumentation tests passed**. Signed release assembly, release vital lint and debug lint passed. The patterned WebView fixture verifies `14.11.2026` transfer; adapter tests reject ISO submission and preserve a manual dotted override.
- Verified package `dev.shoko.remote`, version `0.1.6`, code `7`, non-debuggable APK and the same v2 signing certificate as the previous update. Installed over 0.1.5 on the owner's phone, retaining the data directory and original first-install timestamp. The updated app is running with no startup AndroidRuntime/ShokoAniDb errors observed.
- Local artifact: `artifacts/release/Shoko-Remote-0.1.6.apk`; SHA-256 `FFF097B237A87A766077CFC61045DC6E8C0A2697EB1774C6EAF4C8128AA7B920`. No live contribution was submitted by automated checks.

### Signed 0.1.7 navigation indicator correction

- The owner reported that a live submission worked and Shoko recognized the last file, but the bottom bar appeared to remain on AVDump after returning to Files. A read-only inspection subsequently showed Files selected; the persistent mismatch was not reproduced on that already-settled screen.
- Extracted a stateless bottom bar, resolved the displayed screen and selected tab from the same destination, reset retained bar interaction/animation state on destination changes, and added a visible active-tab indicator.
- **9 physical-device instrumentation tests passed**. The automatic-reset test now waits for foreground recognition polling without tapping Refresh and asserts Files is the only selected tab, before and after Activity recreation. Additional assertions cover manual navigation, programmatic Continue, and a guarded AniDB redirect that must keep AVDump selected.
- Signed release assembly, release vital lint, debug lint and whitespace checks passed. Verified the non-debuggable 0.1.7 APK (code 8) and matching v2 signing certificate, then installed over 0.1.6 with existing data retained. The running app's UI hierarchy and screenshot show only Files selected, with the visible Files highlight. No startup AndroidRuntime/ShokoAniDb errors were observed.
- Installed validation artifact: `artifacts/release/Shoko-Remote-0.1.7.apk`; SHA-256 `4361B611B6EC89FBD6AEBAE469A0CBEF12B5C2BEFC70E7BD474F77604CDECE01`. The publication build uses the same app code, rebuilt from the release commit; distribution hashes are provided with the release assets.

The records below describe earlier releases and their historical phone checks.

## Executed

- Gradle debug APK assembly: passed.
- Signed release APK assembly and APK Signature Scheme v2 verification: passed for 0.1.3 and the 0.1.4 credit update. The current release package is `dev.shoko.remote`, version `0.1.4`, and is not debuggable.
- JVM unit tests: **52 passed** (filename-derived anime titles, optional parser failure recovery, persisted manual queries, automatic/debounced searches, cancellation and stale-response protection, plus the earlier Shoko REST, dump, ED2K, draft and server-binding checks).
- Android lint: zero errors. Dependency-update and style suggestions remain.
- AniDB JavaScript fixture tests: **14 passed**, including live choice extraction, multiple tracks, review edits, disabled-field and CSS-hidden metadata preservation, unknown-field rejection, and no final submit operation.
- Phone instrumentation: **6 passed** on Samsung Galaxy S24 Ultra, Android 17/API 37. Four direct parser tests cover Android pattern compilation, bracket variants, numeric and season titles, and saved-workspace recovery. The smoke test covers passwordless sign-in, selection, dump confirmation, automatic anime search and Activity recreation without duplicate commands or searches. The WebView test covers native-to-browser transfer with multiple tracks and no automatic Next or submission.
- The Shoko-themed release was installed and launched successfully on the physical phone. Its login screenshot was inspected: Sora typography, gray panels, blue accent and dark inputs are present; all sign-in fields and the connect button are visible without clipping.
- Live AniDB form loading and the native editor were observed working after the 0.1.1 metadata correction. Group, Template, CRC/Hash Status, Quality, Source, ED2K links and the Advanced section were all observed in the native UI. The existing AniDB session and Shoko connection survived the update.
- Version 0.1.2 was checked on the desktop only and missed an Android ICU pattern-syntax difference, causing a launch crash when restoring into automatic anime search. Version 0.1.3 corrects the patterns and adds direct Android regression coverage.
- The signed 0.1.3 release was installed over the existing app without clearing data. Two cold starts restored the user's real saved query and displayed search results without a crash; the current process had no crash-buffer entries. No real dump or AniDB submission was triggered by these release checks.
- On November 10, 2026, the owner confirmed the installed update was functioning as expected and requested a stable release. Version 0.1.3 is designated stable for the current personal-use scope. This confirmation does not establish every remaining live-service or Android-version check below.
- The 0.1.4 update adds only an About dialog, development credit and release metadata. Release assembly, 52 JVM tests and lint passed; the signed APK's version and embedded credit resource were checked. Phone instrumentation and cold-start observations above apply to 0.1.3; 0.1.4 was not installed on a phone during this update.

## Live-service checks still required

- Real-server sign-in and unrecognized-file listing were observed working in the installed release. The user also reported completing an actual AVDump on November 10.
- Verify AniDB's acceptance of a complete contributed batch; displaying its form does not establish acceptance of the integration or the submission.
- Compare the full native editor with the signed-in live page, including group search, template effects, all dropdown choices, and track limits.
- Verify one intended subset through native Next, inspect detected EPNO/version and editable date defaults, and submit through native Add Files or optional official review.
- Observe the real response and verify that Shoko eventually recognizes the submitted file.

No real AVDump or AniDB contribution is performed by automated tests. A page navigation or a user-reported submission is not treated as confirmed database acceptance.

## Recovery acceptance

- Draft survives Activity recreation and process restart.
- Persisted dump outcomes are reconciled through fresh file metadata.
- Terminal live events cannot be replaced with stale running-status reads.
- Failed requests and unknown outcomes remain distinct; no dump/submission write is replayed automatically.
- Review edits match file identities before being restored.
- Removed track fields are not transferred into a smaller track configuration.
- Expired credentials return the app to sign-in.

## Emulator limitation

This Windows host does not have emulator acceleration available. Software emulator attempts failed before Android booted; see `tools/EMULATOR-NOTES.md`. Physical-phone checks are used instead. Testing Android 8 specifically remains a later compatibility check unless a suitable device becomes available.
