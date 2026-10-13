# Term Organize for Android

Unofficial Android app for searching Northeastern University courses, building a weekly schedule, importing a degree-plan PDF, and checking that plan against the academic catalog.

This is a Kotlin and Jetpack Compose port of [Term Organize](https://github.com/AlexmChadwick/Northeastern-Term-Organize). It is **not affiliated with Northeastern University or Sandbox**. Course information comes from Northeastern’s public class search and academic catalog. It is not an official audit, registration, or advising tool.

The app is free software under the GNU Affero General Public License, version 3 only. Portions of the Banner client, catalog builder, requisites, schedule generator, and seat-alert rules are derived from [SearchNEU](https://github.com/sandboxnu/searchneu). See [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE). Course and catalog text is © Northeastern University and is not covered by the AGPL grant.

## What it does

- Search the bundled Fall 2026 catalog with shorthand (`orgo`, `fundies`, `linalg`), course codes, CRNs, and typo tolerance, plus subject, NUpath, level, seat, day, and instruction filters.
- Course pages with prerequisite and corequisite trees, “prerequisite for”, sections, seats, and waitlists. Refresh one course from Banner when the network is available, and check older terms.
- Schedule plans, a week calendar, conflict detection, a preferences survey, a deterministic description of the week (in place of Apple Intelligence), ranked results with reasons, and suggestions when nothing fits.
- Export a schedule as `.ics`, hand the first meeting to Android Calendar, share a schedule image, and copy CRNs.
- Import a degree-audit “View Plan” PDF (the system picker, or Open with), edit it, check prerequisites and repeats, export the same layout, and autofill a schedule from the matching semester.
- Load program requirements from catalog.northeastern.edu and run a degree audit, including NUpath, substitutions, waivers, equivalences, and title matches for renumbered courses.
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

Install `app/build/outputs/apk/debug/app-debug.apk`, or open the project in Android Studio and run the `app` configuration. Debug and release are signed with the local keystore `app/termorganize-debug.jks` (alias and passwords `termorganize`). That key is only for local builds.

```bash
./gradlew :app:testDebugUnitTest   # JUnit + Robolectric
./gradlew :app:lint
./gradlew :app:assembleRelease
```

Unit tests load the bundled catalog from `app/src/main/assets/catalog/`. The Android Gradle Plugin stores that gzip as plain JSON in the APK; the app opens either name. Instrumented tests live in `app/src/androidTest` and need a device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

There is no emulator image in this environment, so the UI flow was verified with Robolectric instead.

## Data

Plans, the watchlist, and settings are one JSON document in Room (`term-organize.db`). Widget state is `widget-schedule.json` in app files. Nothing is uploaded to a Term Organize server. Live seats come from `nubanner.neu.edu` when you refresh or when a seat check runs. Program requirements come from `catalog.northeastern.edu` when you load a program.

## License

[AGPL-3.0-only](LICENSE). If you distribute the app you must offer the corresponding source under the same license.
