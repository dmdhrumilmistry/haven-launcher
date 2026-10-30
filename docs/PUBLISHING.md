# Publishing Haven

Haven ships three ways. All of them come from the same release tag.

| Channel | Build | Signed by | Notes |
|---|---|---|---|
| GitHub releases | `standard` and `full` APKs | Your key (CI secrets) | Automatic on every `vX.Y.Z` tag |
| F-Droid | `full` | F-Droid's key | F-Droid builds from source on its own servers |
| Google Play | `full` app bundle (`-play.aab`) | Play App Signing | Manual first upload, then optional automatic uploads |

Because F-Droid signs with its own key, people can't update a GitHub install with the F-Droid
version (or the other way around) without uninstalling first. Play and GitHub can match if you
choose to upload your own key as the app signing key in Play Console.

## Releasing a new version

1. In `app/build.gradle.kts`, bump `versionName` (e.g. `1.4.0`) and `versionCode`
   (`major*10000 + minor*100 + patch`, e.g. `10400`).
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters max, shown on
   F-Droid, Play and the GitHub release).
3. Merge to `main`, then tag: `git tag v1.4.0 && git push origin v1.4.0`.

The Release workflow refuses to run if the tag and the version in `build.gradle.kts` don't match.

## F-Droid (one-time)

F-Droid needs a merge request from you; after that, new tags are picked up automatically
(`UpdateCheckMode: Tags`).

1. Make sure the tag in `docs/fdroid/app.haven.launcher.yml` (`commit: v1.3.0`) is released.
2. Create a GitLab account and fork https://gitlab.com/fdroid/fdroiddata
3. Copy `docs/fdroid/app.haven.launcher.yml` to `metadata/app.haven.launcher.yml` in your fork.
4. Optional but recommended, test it locally with fdroidserver:
   `fdroid readmeta && fdroid lint app.haven.launcher && fdroid build -v -l app.haven.launcher`
5. Open a merge request titled "New app: Haven". Reviewers may ask questions; answers to common ones:
   - No proprietary libraries, no tracking, no network access.
   - The accessibility service and notification listener are optional, off by default, and
     disclosed in-app before the user is sent to system settings.
   - Store text and graphics are in `fastlane/metadata/android/en-US`.
6. Screenshots: add 2 to 8 phone screenshots to
   `fastlane/metadata/android/en-US/images/phoneScreenshots/` (`1.png`, `2.png`, ...). F-Droid picks
   them up from the repo.

## Google Play (one-time)

1. Create a Play Console developer account (one-time USD 25 fee, identity verification).
2. Create the app: name "Haven Launcher", free, app (not game).
3. **Main store listing**: copy from `fastlane/metadata/android/en-US/`
   (`title.txt`, `short_description.txt`, `full_description.txt`, `images/icon.png`,
   `images/featureGraphic.png`). Add at least 2 phone screenshots.
   Category: **Personalization**.
4. **Privacy policy URL**:
   `https://github.com/dmdhrumilmistry/haven-launcher/blob/main/PRIVACY.md`
5. **App content**:
   - Data safety: **No data collected, no data shared.** (Haven has no internet permission.)
   - Ads: no. Target audience: 13+ (or "all ages" if you prefer). Content rating questionnaire:
     a utility with no user-generated content.
   - **Accessibility API declaration**: required because the full build declares an accessibility
     service. Explain: "Optional double-tap-to-lock gesture on the home screen. The service only
     performs the global lock-screen (and open-notifications) action when the user double-taps or
     swipes down. It doesn't read window content (`canRetrieveWindowContent=false`), listens to no
     events, and the app shows a prominent disclosure before sending the user to settings."
     If Play rejects this use, publish the `standard` flavor instead (`bundleStandardRelease`):
     everything works except double-tap to lock and notification badges.
6. **Release**: download `haven-launcher-vX.Y.Z-play.aab` from the GitHub release and upload it to
   an Internal testing release. Opt in to Play App Signing (the default). Then promote to
   Closed/Open testing and Production when ready. New personal developer accounts must run a closed
   test with at least 12 testers for 14 days before Production access.

### Optional: automatic Play uploads

After the first manual upload:

1. Play Console > Setup > API access: link a Google Cloud project, create a service account, and
   grant it "Release to testing tracks" for Haven.
2. Create a JSON key for the service account and add it as the repository secret
   `PLAY_SERVICE_ACCOUNT_JSON` (`gh secret set PLAY_SERVICE_ACCOUNT_JSON < key.json`).

From then on, every release tag also uploads the bundle to the **internal** track with the
changelog as "What's new". Promote it in Play Console when you're happy.
