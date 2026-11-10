# Shoko Remote for Android

A native Android client for Shoko Server, starting with **unrecognized files → AVDump → AniDB Mass Add → recognition checks**.

**Stable release: [Shoko Remote 0.1.3](https://github.com/ichigoteguz/shoko-android/releases/tag/v0.1.3)**, confirmed working by the owner on November 10, 2026. Download `Shoko-Remote-0.1.3.apk` from the release and install it over your existing copy to keep your connection and drafts. See [release notes](docs/releases/v0.1.3.md) for validation and remaining compatibility checks. Full WebUI parity remains on the [roadmap](docs/FEATURES.md).

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
2. In **Files**, search and select a release. Continue to **AVDump**.
3. Run AVDump for intact, publicly released, unmodified files. The work runs on your server; you can leave the app and return. Previously dumped files can skip this step.
4. Continue to **AniDB**. The search box fills with the anime title inferred from your selected files, and results load automatically. Check or edit the suggested title, or enter an AniDB ID. Your manual edits are kept when you return to the screen.
5. Sign in on the official AniDB page inside the app. Tap **Use app editor** to load the current native controls.
6. Choose the release group/template, CRC status, quality/source, and check the filled ED2K links. Use **Advanced** for track and video options.
7. Tap **Continue on AniDB**, then the website's **Next** button. Tap **Use app editor** again for mobile episode/version/release-date editing.
8. Tap **Review on AniDB**, check the populated official review, and press **Add Files** yourself.
9. Tap **I submitted · check Shoko**. This records your report and queues recognition checks. It does not claim AniDB accepted the contribution; actual recognition is shown separately.

If AniDB asks for sign-in or a browser check, complete it on its official page. If the form changes or a value cannot be transferred, the app preserves your draft and shows the problem. It never silently removes unknown options or automatically presses the final Add Files button.

**Submission integration status:** the live AniDB form and native editor have been verified on the connected phone. Native-to-form transfer is covered by local form tests and a real Android WebView test. AniDB's acceptance of a complete contribution and this assisted workflow still need user-operated live validation; do not infer acceptance from the use of the official form. No private endpoint or raw create-file POST is implemented. The official **Next** action is user-operated because its current live implementation could not be audited in this environment.

## Drafts and templates

Drafts, selected files, and activity are stored in Room on the phone. Custom templates support add, edit, delete, default selection, and reset. They retain release/track options; anime, group, and file links belong to the current batch. Desktop browser cookie templates do not automatically transfer.

Use **New batch** to clear the current file selection and draft. Changing anime keeps the selected files and links while clearing anime-specific form data.

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
