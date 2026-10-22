# Term Organize for Android

Unofficial Android app for searching Northeastern University courses, building a weekly schedule, importing a degree-plan PDF, and checking that plan against the academic catalog.

This is a Kotlin and Jetpack Compose port of [Term Organize](https://github.com/AlexmChadwick/Northeastern-Term-Organize). It is **not affiliated with Northeastern University or Sandbox**. Course information comes from Northeastern’s public class search and academic catalog. It is not an official audit, registration, or advising tool.

The app is free software under the GNU Affero General Public License, version 3 only. Portions of the Banner client, catalog builder, requisites, schedule generator, and seat-alert rules are derived from [SearchNEU](https://github.com/sandboxnu/searchneu). See [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE). Course and catalog text is © Northeastern University and is not covered by the AGPL grant.

## Install

Term Organize is an early beta. It is unofficial and not affiliated with Northeastern University. It is not on Google Play.

1. When available, download `term-organize-<version>.apk` from the [Releases page](https://github.com/AlexmChadwick/term-organize-android/releases). Until then, build it yourself (see "Build and run" below).
2. On your phone, allow your browser or file manager to install apps: Settings > Apps > Special app access > **Install unknown apps**.
3. Open the APK and tap Install.

## What it does

- Search the open term with shorthand (`orgo`, `fundies`, `linalg`), course codes, CRNs, and typo tolerance, plus subject, NUpath, level, seat, day, and instruction filters. Fall 2026 and Spring 2027 ship in the app. A fresh install opens the newest bundled term. Other Banner terms download on demand and stay cached. If the main search finds nothing, an AndroidX AppSearch index is checked.
- Course pages with prerequisite and corequisite trees, “prerequisite for”, sections, seats, and waitlists. Refresh one course from Banner when the network is available, and check older terms.
- Schedule plans, a week calendar, conflict detection, a preferences survey, a deterministic description of the week (in place of Apple Intelligence), the full scoring weight table and sampler, ranked results with reasons, and suggestions when nothing fits.
- Export a schedule as `.ics` with one event per meeting and each final. Add every meeting to Android Calendar (calendar permission), share a schedule image, and copy CRNs.
- Import a degree-audit “View Plan” PDF (the system picker, or Open with), edit it, check prerequisites and repeats, export the same layout, and autofill a schedule from the matching semester.
- Load program requirements from catalog.northeastern.edu and run the degree audit, including the optimal rebalance, NUpath, substitutions, waivers, petition course-number questions, equivalences, and title matches for renumbered courses.
- Offering history for the last two years, and suggestions to move or swap a course that is not taught in the semester where it is planned.
- Seat alerts through WorkManager, about every 15 minutes, with a notification when a seat or waitlist spot opens.
- Free rooms and professor pages with a teaching calendar.
- Glance home-screen widgets: Up next, and today’s agenda.
- Deep links `termorganize://` and `neuclasssearch://` for search, schedule, course, and section. App shortcuts for search, the schedule, and the next class.
- Settings for dynamic color and dark mode, JSON backup and restore. iCloud has no equivalent here.

Phone and tablet layouts use Material 3. On a wide window the navigation moves from a bar to a rail.

## Build and run

Requirements: JDK 17, Android SDK 36, build-tools 36.0.0.

```bash
# local.properties, if the SDK is not already on ANDROID_HOME
echo "sdk.dir=$HOME/android-sdk" > local.properties

./gradlew :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk`, or open the project in Android Studio and run the `app` configuration. Debug and local release builds use the Android debug keystore on the machine. No signing keys are stored in this repository.

```bash
./gradlew :app:testDebugUnitTest   # JUnit + Robolectric
./gradlew :app:lint
./gradlew :app:assembleRelease
```

GitHub Actions (`.github/workflows/ci.yml`) runs those unit tests, lint, and `assembleDebug` on Ubuntu with JDK 17, and uploads the debug APK.

Unit tests load the bundled catalog from `app/src/main/assets/catalog/`. The Android Gradle Plugin stores that gzip as plain JSON in the APK; the app opens either name. Instrumented tests live in `app/src/androidTest` and need a device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

The home flow is also covered by Robolectric (`AppUiTest`). An Android 34 AOSP ATD image (`system-images;android-34;aosp_atd;x86_64`) can be installed with `sdkmanager` and booted as AVD `nto`. KVM boots of that image hang before the guest kernel on this host. A software boot works with `~/.android/advancedFeatures.ini` disabling `WiFiPacketStream`, `NetsimWebUi`, `NetsimCliUi`, and `Vulkan`, and with `emulator -avd nto -no-window -no-audio -no-boot-anim -gpu off -no-snapshot -no-metrics -accel off -memory 3072 -cores 2 -feature -WiFiPacketStream`. `connectedDebugAndroidTest` needs that emulator (or another device) online. The first catalog decode on that software emulator takes several minutes, so the device test waits for the home screen with UiAutomator instead of the Compose test rule.

## Data

Plans, the watchlist, and settings are one JSON document in Room (`term-organize.db`). Widget state is `widget-schedule.json` in app files. Downloaded catalogs are `files/catalogs/term-<code>.json`. Offering history is `files/course-info/offering-history.json`. Nothing is uploaded to a Term Organize server. Live seats, term downloads, and offering history come from `nubanner.neu.edu`. Program requirements come from `catalog.northeastern.edu` when you load a program.

## License

[AGPL-3.0-only](LICENSE). If you distribute the app you must offer the corresponding source under the same license.
