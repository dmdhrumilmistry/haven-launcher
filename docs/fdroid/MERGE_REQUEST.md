## Abstract for new app inclusion

### Please introduce the functions and usage of your app in summary, and also attach some screenshots.

Haven is a calm, clean home screen (launcher) that feels like stock Android: an icon grid with folders and widgets on the home screen, and a swipe-up app drawer with instant search. It has **no internet permission**, no ads, no tracking and no proprietary libraries (Kotlin + Jetpack Compose / AndroidX only).

Main features:
- Home: clock/date/next alarm, icon grid or list, drag and drop to rearrange, folders (drop an app onto another), widgets (add/resize/reorder), pinned shortcuts
- Drawer: accent-insensitive search with initials matching and a built-in calculator, grid or list, A-Z scroller (ICU, works for every script) or sort by most used, user categories as tabs, drawer folders, suggested apps learned on-device
- Profiles: Personal / Work / Private space tabs (swipe between them), pause/resume work apps, lock/unlock Android 15 private space, cloned apps tagged
- Privacy and focus: hidden apps (optionally locked with the device's biometrics/screen lock), pause apps now or on a schedule, link to Digital Wellbeing app timer
- Customisation: icon packs (ADW/Nova appfilter), icon shapes, themed icons, notification badges (dot or count), Material You, settings export/import

Optional, off-by-default features that use sensitive APIs, each with an in-app prominent disclosure before the user is sent to system settings:
- Accessibility service: only for double-tap to lock (global action). `canRetrieveWindowContent=false`, no event types.
- Notification listener: only counts notifications per app for icon badges; never reads, stores or sends content.

Store metadata (summary, description, icon, feature graphic, changelog) is in the upstream repo under `fastlane/metadata/android/en-US`. Phone screenshots will be added there shortly.

### Please elaborate why you wrote this app. Please highlight the unique functions it provides over other similar apps in F-Droid.

I wanted a launcher that looks and behaves like stock Android out of the box, but with privacy built in and the organisation features people usually need several apps for. Compared with other F-Droid launchers, Haven combines in one app: first-class work profile **and** Android 15 private space support with swipeable tabs, user categories as drawer tabs plus drag-and-drop folders in both home and drawer, hidden apps locked behind the device's own biometrics, scheduled app pausing, and icon packs, all without any network access.

## Checklist

### Policy

* [x] The app complies with the [inclusion policy](https://f-droid.org/docs/Inclusion_Policy). _Pay special attention to Quality point 6._
* [x] The original app author has been notified (and does not oppose the inclusion). If you are not the author, please paste the link of the reply from the author. _(I am the author.)_
* [x] The upstream app source code repo contains the app metadata in a [Fastlane](https://gitlab.com/snippets/1895688) or [Triple-T](https://gitlab.com/snippets/1901490) folder structure. The summary and description must be included and images, icon, and changelog should also be provided for better user experience. The `en-US` locale must be included. _(Screenshots to follow.)_

### Docs

* [x] Please read [the guide](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md) first if this is your first contribution.
* [x] Please make sure your metadata follows the best practice in [our templates](https://gitlab.com/fdroid/fdroiddata/tree/master/templates).
* [x] Please read the [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/) and make sure your metadata is valid.
* [x] Please read the [Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

### Merge Request Setup

* [x] The title of this merge request should follow "New app: app name" format.
* [x] Please make sure your fdroiddata fork is public and your branch is not protected. See <https://docs.gitlab.com/user/project/repository/branches/protected/>.
* [x] Please read [our Git guide](https://gitlab.com/fdroid/wiki/-/wikis/Tips-for-fdroiddata-contributors/Git-Usage) if you don't know how to rebase your branch. Don't rebase your branch if there is no conflict.
* [x] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request _(none exist)_
* [x] Please only submit one app in one MR.

### Metadata

* [x] Metadata must be put in `metadata/<applicationId>.yml`.
* [x] Metadata must be a valid YAML file. _(`fdroid lint` and `fdroid rewritemeta` clean)_
* [x] Metadata must use LF as line ending.
* [x] Don't add summary/description/changelog/images or anything that should be provided in upstream repo. Please check the Changes tab to make sure there is no other unrelated files added in the MR.
* [x] Releases are tagged and auto update is enabled unless there is a special reason.
* [x] There is an issue tracker and contact info of the author so that we can report bugs and contact the author.
* [x] An AuthorName must be added. It doesn't need to be the real name.
* [x] External repos are added as git submodules instead of srclibs. _(No external repos.)_
* [x] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds). _(`Binaries` + `AllowedAPKSigningKeys` set; upstream release APKs are built by GitHub Actions with JDK 17 from the tagged commit, `dependenciesInfo` disabled.)_
* [x] Setup abi split if the APK is large and the splitted ones can be much smaller. _(No native code; single ~1.8 MB APK.)_
* [x] Only the latest versions should be kept in the metadata before it's merged. If you update the metadata, please replace the old versions with the new ones.
* [x] Don't add any disabled versions in the metadata.
* [x] The `commit` field should be the full hash. Please don't use tag or branch in commit.

### Pipeline

* [ ] All pipelines should pass.
* [ ] All warnings and errors in the Reports tab should be fixed or explained.
* [x] F-Droid CI runners are under GitLab's FOSS program, so there's no need for you to pay for any CI time. If Gitlab starts asking for phone numbers or credit cards don't submit anything, just leave a note in the MR so we know we need to trigger the CI.

---------------------

/label ~"New App"
