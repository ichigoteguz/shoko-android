# Shoko Remote for Android

A native Android client for Shoko Server, starting with **unrecognized files → AVDump → AniDB Mass Add → recognition checks**.

Developed with OpenAI Codex, in collaboration with ichigoteguz. The same credit is available in the app's **About Shoko Remote** dialog (the info button in the top bar).

**Latest release: [Shoko Remote 0.1.7](https://github.com/ichigoteguz/shoko-android/releases/tag/v0.1.7)**. This adds automatic refreshes and batch resets, separate AVDump/AniDB selections, native Next/Add Files, corrected release dates (`dd.mm.yyyy`), and a bottom navigation highlight that follows the displayed page. Download `Shoko-Remote-0.1.7.apk` and install it over your existing copy to keep the connection and drafts. See [release notes](docs/releases/v0.1.7.md) and [verification notes](docs/VERIFICATION.md). Full WebUI parity remains on the [roadmap](docs/FEATURES.md).

The app uses the WebUI's **Shoko Gray** theme: its blue accent, gray panels, dark inputs, Sora typography, and 8dp corners. It keeps that appearance in both Android system modes. Server-provided custom CSS overrides are not imported into the native theme.

The everyday editor puts group, template, CRC/hash status, quality, source, and ED2K links first. Advanced controls expose the current AniDB form's video flags, audio/subtitle tracks, languages, and accessibility flags. Dropdown choices come from the signed-in AniDB page rather than a fixed, potentially outdated catalog.

## Requirements

- Android 8 or newer, with an updated Android System WebView.
- A reachable Shoko Server 5.3.3 and Shoko user account. Passwordless local accounts are supported.
- Your phone on the server's home network or VPN.
- AniDB username and AVDump key already configured **on Shoko Server**.
- An AniDB account for the separate website sign-in.

## Use the app

1. Install the APK, open **Shoko Remote**, and enter the full server address, such as `http://192.168.1.10:8111`. Sign in with your Shoko username and password.
2. In **Files**, select an AVDump pool. It can contain files from several anime. Continue to **AVDump**. Files refreshes whenever you return to it or resume the app there.
3. Run AVDump for intact, publicly released, unmodified files. The work runs on your server; you can leave the app and return. Previously dumped files can skip this step.
4. On **AVDump**, check the files for one anime and tap **Choose files for AniDB**. Only that subset needs a completed dump. The anime search suggestion and ED2K links use that subset.
5. Select the anime. The app loads AniDB in the background and opens its native editor automatically. If sign-in or browser verification is needed, complete it on the official page; the app returns to the editor when the form is ready.
6. Choose the release group/template, CRC status, quality/source, and check the filled ED2K links. Use **Advanced** for track and video options.
7. Tap **Next** in the app. The lower section changes to per-file EPNO, version and release-date controls, retaining AniDB's episode/version detection. Blank dates initially default to today's local date (`dd.mm.yyyy`); each date remains editable.
8. Tap **Add Files** to submit through AniDB's official control. The app displays explicit success or error messages when recognized. **Review on AniDB** remains available for optional manual review.
9. Tap **I submitted · check Shoko** to return to refreshed AVDump status. That subset becomes a pending group, so you can choose the next anime while Shoko checks recognition. Pending files cannot be selected again. Recheck individual pending groups manually if necessary.
10. Once every file in the pool is recognized by Shoko, the app starts a new batch and returns to a refreshed Files page with its search cleared.

If AniDB asks for sign-in or a browser check, complete it on its official page. If the form changes or a value cannot be transferred, the app preserves your draft and shows the problem. It never silently removes unknown options. Next and Add Files run only after the corresponding user action; interrupted submissions are never automatically retried.

**Submission integration status:** the owner reported a successful live submission and subsequent Shoko recognition on the installed update. Native Next/Add Files and result handling also passed JavaScript and physical-device WebView fixture tests. Browser verification prevented independent inspection of the current signed-in page during development; automated tests use fixtures. No private endpoint or raw create-file POST is implemented. The app treats only explicit AniDB result messages as acceptance and tracks Shoko recognition separately.

## Drafts and templates

Drafts, selected files, and activity are stored in Room on the phone. Custom templates support add, edit, delete, default selection, and reset. They retain release/track options; anime, group, and file links belong to the current batch. Desktop browser cookie templates do not automatically transfer.

Use **New batch** to clear the pool, pending groups and draft. **Change selected files** discards just the active AniDB draft and subset, preserving the pool and dump results. Changing anime keeps the subset and links while clearing anime-specific form data. Older workspaces restore their AniDB subset from matching draft links, or their previous selection when no links exist.

## Build

The project uses JDK 17, Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.21, and Android SDK 36. Dependencies are pinned. On Windows:

```powershell
.\tools\setup-android.ps1
.\tools\build.ps1
```

The setup script installs portable tools beside the project, verifies downloaded tool archives, and does not change global environment variables. It requires accepting Android's SDK license terms. Android Studio can also open the project with a suitable JDK/SDK.

The default build runs `assembleDebug`, `testDebugUnitTest`, and `lintDebug`. The test APK uses a separate application ID, `dev.shoko.remote.debug`, and the name **Shoko Remote Test**.

For a personally signed installable build:

```powershell
.\tools\build-release.ps1
```

This creates a local signing key once. Keep `.signing/` and `release-signing.properties` privately for future APK updates. They are ignored by Git and excluded from the source archive.

Release output: `app/build/outputs/apk/release/app-release.apk`.

## Tests

```powershell
.\tools\build.ps1 -Tasks testDebugUnitTest
cd tests/anidb
pnpm install --frozen-lockfile
pnpm test
```

With a connected Android test device:

```powershell
.\tools\build.ps1 -Tasks connectedDebugAndroidTest
```

Instrumentation uses the isolated **Shoko Remote Test** package. Its app smoke test clears only that test app's session/drafts and runs against a loopback mock server. The WebView test loads a local fixture with an AniDB base URL and makes no contribution. Neither test connects to a production Shoko server or submits files to AniDB.

See [verification notes](docs/VERIFICATION.md) for executed checks and remaining live checks, and [the feature checklist](docs/FEATURES.md) for later WebUI parity work.

## Data and connections

- The Shoko API key is encrypted with an Android Keystore key. Shoko passwords are used only to sign in.
- AniDB sign-in and cookies stay in its dedicated WebView; the app does not copy AniDB credentials into its native database.
- Local HTTP is allowed for the configured Shoko origin. REST credentials are origin-bound and redirects are not followed.
- AniDB editing is restricted to its official HTTPS origin; file/content access and mixed HTTP content are disabled.
- App data is excluded from cloud backup and device transfer.
- AVDump starts and recognition checks are explicit actions. Ambiguous writes are never automatically repeated.

This is an independent personal client, not an official ShokoAnime or AniDB release.
