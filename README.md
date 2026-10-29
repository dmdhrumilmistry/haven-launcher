# Haven Launcher

A calm, clean Android home screen. No ads, no tracking, no accounts, and **no internet permission**, so nothing ever leaves the phone.

Built with Kotlin and Jetpack Compose (Material 3). Supports Android 8.0 (API 26) and up, targets Android 16 (API 36).

## What it does

**Home screen**
- Clock and date (tap the clock for alarms, tap the date for the calendar), plus your next alarm if it's within 18 hours
- **Widgets**: long-press empty space > Widgets. Long-press a widget to resize, reorder, reconfigure or remove it. Apps can also add widgets and shortcuts to the home screen themselves
- Long-press and drag apps to rearrange them (or lock the layout to prevent accidents)
- Favorite apps as a stock-Android style icon grid (default, 3 to 6 columns), names only, or icons with names. Align left, center or right; place them in the middle or at the bottom (easier to reach)
- On first run it adds your default phone, messages, browser and camera, so the home screen isn't empty
- Text color follows the wallpaper automatically (light or dark), with an optional wallpaper dim
- Supports pinned shortcuts from other apps (for example, a browser's "Add to home screen")

**App drawer**
- Swipe up anywhere (or tap the arrow) to open. The drawer follows your finger and settles with a spring
- Search (the keyboard can open automatically; off by default). It ignores accents, matches initials ("gm" finds Google Maps) and camel case ("app" finds WhatsApp), and still finds renamed apps by their original name
- Suggested apps row, learned only on the phone; a dot marks newly installed apps you haven't opened
- Calculator: type `12*7+3` in search and tap the answer to copy it
- Enter opens the top result. Optional "open when only one app matches", which never fires while you're deleting
- If nothing matches: search the web or the app store
- List or grid, A-Z fast scroller (localized with ICU, so it works for Japanese, Chinese, etc.)
- Long-press any app: its shortcuts, add to home, rename, hide, app info, uninstall

**Work profile, private space and clones**
- Separate **Personal / Work / Private** tabs: swipe between them or tap (or merge work into the main list)
- **Clone app** opens the phone's own cloning screen where one exists (Android 14+ cloned apps, Samsung Dual Messenger, Xiaomi Dual apps, and similar screens found in the phone's Settings app). Android doesn't let third-party launchers create clones themselves; clones show in the drawer with a "Clone" tag
- Work apps can be paused and resumed from the drawer. Paused apps show greyed out, and tapping one offers to turn work apps back on
- Android 15+ private space: lock and unlock from the drawer. It never mixes into other lists, and it respects the system's "hide when locked" setting
- Every non-personal app carries the system's own badge, so it always matches Settings

**Customization**
- Theme (system, light, dark), Material You colors, text size, drawer background opacity
- Icon size, icon shape (system, circle, squircle, rounded square), themed monochrome icons (Android 13+)
- Gestures: swipe down for notifications or search, double-tap to lock (optional)
- Hidden apps in their own **Hidden** tab (optional), locked with your fingerprint, face or screen lock if you like
- Renamed apps, reorderable home apps
- Export and import all settings as a JSON file; reset to defaults

## Edge cases handled

- Apps installed, removed, updated, enabled/disabled, moved to SD card, suspended by Digital Wellbeing: all picked up live (bursts are batched into one reload)
- Profiles added, removed, paused, resumed, locked or unlocked; language changes rename apps and re-sort
- Launch failures (app just uninstalled, blocked by admin policy) show a message instead of crashing
- Pressing Home while on the launcher closes the drawer, sheets and search. Back never closes the launcher
- Returning from an app always lands on a clean home screen
- The A-Z scroller is excluded from the back gesture, so dragging it doesn't navigate back
- Corrupted or hand-edited settings fall back to defaults instead of crashing; imports are validated and size-limited
- Missing apps on the home screen (uninstalled or temporarily unavailable) don't leave dead slots, and come back if the app does
- Pinned shortcuts removed from the home screen are unpinned with the owning app too
- Hiding an app also removes it from the home screen
- Accessibility: every gesture has a tappable alternative, long-press menus are exposed as TalkBack actions, and the user's text size multiplies the system font scale

## Permissions, and why

| Permission | Why |
|---|---|
| `EXPAND_STATUS_BAR` | Swipe down to open notifications |
| `REQUEST_DELETE_PACKAGES` | The "Uninstall" action opens the system uninstall dialog |
| `USE_BIOMETRIC` | Fingerprint or face check for locked hidden apps (your PIN works too) |
| `ACCESS_HIDDEN_PROFILES` | Android 15+: lets the home app show the private space entry |
| Accessibility service (**full build only**, off until you enable it) | Only for double-tap to lock. It reads nothing on screen. Haven explains this before sending you to settings |

No internet, no storage, no contacts, no location.

### Standard and full builds

Each release has two APKs, signed with the same key (so you can switch between them):

- **`haven-launcher-vX.Y.Z.apk` (standard, recommended):** everything except double-tap to lock.
- **`haven-launcher-vX.Y.Z-full.apk`:** adds double-tap to lock, which needs an accessibility service. In several countries Google Play Protect blocks installing sideloaded apps that declare one ("App blocked to protect your device"), so use the standard build there.

Locally: `./gradlew assembleStandardDebug` or `./gradlew assembleFullDebug`.

## Build

Needs JDK 17+ and the Android SDK (platform 36).

```bash
./gradlew assembleDebug
# APKs: app/build/outputs/apk/{standard,full}/debug/
```

Install it, then open **Haven Settings** and tap **Set as home app** (or pick Haven when you press Home).

## CI and releases

- **Build** (`.github/workflows/build.yml`): every push to `main` and every pull request builds the debug APK and an unsigned release (so R8/minify problems show up early) and runs lint. The debug APK and lint report are attached to the run as artifacts.
- **Release** (`.github/workflows/release.yml`): pushing a tag like `v1.2.3` builds a signed release APK, verifies the signature and publishes a GitHub release with both APKs (standard and full) and a SHA256SUMS.txt. Tags with a suffix (`v1.3.0-beta.1`) become pre-releases.
- **Dependabot** keeps Gradle dependencies and GitHub Actions up to date weekly.

Versions come from the tag: `v1.2.3` becomes version name `1.2.3` and version code `10203`, so every release can update the previous one. Minor and patch must stay between 0 and 99.

### One-time setup: signing key

Android only installs updates signed with the same key as the original, so create one key and keep it safe forever. Losing it means people would have to uninstall to update.

```bash
keytool -genkeypair -v -keystore haven-release.jks -alias haven \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then add four repository secrets (Settings > Secrets and variables > Actions), for example with the GitHub CLI:

```bash
base64 -w0 haven-release.jks | gh secret set HAVEN_KEYSTORE_BASE64
gh secret set HAVEN_KEYSTORE_PASSWORD   # the store password you chose
gh secret set HAVEN_KEY_ALIAS --body haven
gh secret set HAVEN_KEY_PASSWORD        # the key password (same as store password if you didn't set one)
```

Back up `haven-release.jks` and its passwords somewhere offline, and never commit them.

### Cutting a release

```bash
git tag v1.0.0
git push origin v1.0.0
```

To rebuild an existing tag (for example after fixing the secrets), run the Release workflow manually from the Actions tab and enter the tag.

### Signed builds locally

The same environment variables work on your machine:

```bash
HAVEN_KEYSTORE_PATH=/path/to/haven-release.jks \
HAVEN_KEYSTORE_PASSWORD=... HAVEN_KEY_ALIAS=haven HAVEN_KEY_PASSWORD=... \
./gradlew assembleRelease
```

Without them, `assembleRelease` still works but produces an unsigned APK.

## Project layout

```
app/src/main/java/app/haven/launcher/
  HavenApp.kt              Application: holds the repositories
  MainActivity.kt          The home screen activity
  AddShortcutActivity.kt   Confirms "Add to home screen" requests from other apps
  data/
    AppRepository.kt       Apps and profiles from LauncherApps, live updates
    IconCache.kt           Icon rendering (shapes, themed icons, profile badges) and caching
    LauncherSettings.kt    All preferences, with lenient JSON (de)serialization
    SettingsRepository.kt  Persistence, import/export
    Search.kt              Normalization and ranking
    Models.kt
  system/
    SystemActions.kt       Launching, app info, uninstall, default-launcher role, etc.
    LauncherAccessibilityService.kt
  ui/
    LauncherScreen.kt      Home + drawer, gestures, sheets, dialogs
    LauncherViewModel.kt
    home/                  Home screen, clock, wallpaper color detection
    drawer/                App drawer, A-Z scroller
    components/            Icons, long-press sheets, rename dialog
    settings/              Settings screens
    theme/
```

## Not included (yet)

- Widgets
- Notification dots (they need notification access, which conflicts with the privacy goals; could be added as opt-in)
- Icon packs
