# Verification record

This record distinguishes local fixture verification from real services. Initial phone checks were executed on November 8, 2026; anime-search update checks were executed on November 10, 2026.

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
- Transfer one intended batch to official review, inspect every value, and let the user decide whether to press Add Files.
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
