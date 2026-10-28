# Haven Launcher

A calm, clean Android home screen. No ads, no tracking, no accounts, and **no internet permission**, so nothing ever leaves the phone.

Built with Kotlin and Jetpack Compose (Material 3). Supports Android 8.0 (API 26) and up, targets Android 16 (API 36).

## What it does

**Home screen**
- Clock and date (tap the clock for alarms, tap the date for the calendar), plus your next alarm if it's within 18 hours
- Favorite apps as names, icons with names, or an icon grid. Align left, center or right; place them in the middle or at the bottom (easier to reach)
- On first run it adds your default phone, messages, browser and camera, so the home screen isn't empty
- Text color follows the wallpaper automatically (light or dark), with an optional wallpaper dim
- Supports pinned shortcuts from other apps (for example, a browser's "Add to home screen")

**App drawer**
- Swipe up anywhere (or tap the arrow) to open. The drawer follows your finger and settles with a spring
- Search opens with the keyboard ready. It ignores accents, matches initials ("gm" finds Google Maps) and camel case ("app" finds WhatsApp), and still finds renamed apps by their original name
- Enter opens the top result. Optional "open when only one app matches", which never fires while you're deleting
- If nothing matches: search the web or the app store
- List or grid, A-Z fast scroller (localized with ICU, so it works for Japanese, Chinese, etc.)
- Long-press any app: its shortcuts, add to home, rename, hide, app info, uninstall

**Work profile, private space and clones**
- Separate **Personal / Work / Private** tabs (or merge work into the main list)
- Work apps can be paused and resumed from the drawer. Paused apps show greyed out, and tapping one offers to turn work apps back on
- Android 15+ private space: lock and unlock from the drawer. It never mixes into other lists, and it respects the system's "hide when locked" setting
- Every non-personal app carries the system's own badge, so it always matches Settings

**Customization**
- Theme (system, light, dark), Material You colors, text size, drawer background opacity
- Icon size, icon shape (system, circle, squircle, rounded square), themed monochrome icons (Android 13+)
- Gestures: swipe down for notifications or search, double-tap to lock (optional)
- Hidden apps, renamed apps, reorderable home apps
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
| `ACCESS_HIDDEN_PROFILES` | Android 15+: lets the home app show the private space entry |
| Accessibility service (optional, off by default) | Only for double-tap to lock. It reads nothing on screen. Haven explains this before sending you to settings |

No internet, no storage, no contacts, no location.

## Build

Needs JDK 17+ and the Android SDK (platform 36).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install it, then open **Haven Settings** and tap **Set as home app** (or pick Haven when you press Home).

For a release build, add your own signing config in `app/build.gradle.kts` and run `./gradlew assembleRelease`.

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
