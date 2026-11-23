# Term Organize for Android

**Unofficial.** Term Organize is not affiliated with or endorsed by Northeastern University. It is not an official audit, registration, or advising tool.

This is a Kotlin and Jetpack Compose app (Material 3) for searching Northeastern courses, building a weekly schedule, and checking a degree plan against catalog requirements. It is an Android port of [Term Organize](https://github.com/AlexmChadwick/Northeastern-Term-Organize). The in-app disclaimer (`app/src/main/res/values/strings.xml`) also says it is not affiliated with Sandbox. Course listings come from Northeastern's public Banner class search. Program pages, when you load one, come from the public academic catalog.

The app is free software under the GNU Affero General Public License, version 3 only. Portions of the Banner client, requisites, schedule generator, and seat-alert rules are derived from [SearchNEU](https://github.com/sandboxnu/searchneu). See [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE). Course and catalog text is published by Northeastern University and is not covered by the AGPL grant.

There is no store listing in this repository. Build an APK and install it yourself. The version in `app/build.gradle.kts` is 1.1.0 (`versionCode` 2). Settings shows that version name.

Paths below are under `app/src/main/java/app/termorganize/` unless another path is given.

## What it does

**Search.** The search field takes a course, a professor, a CRN, or a shorthand such as `orgo`, `fundies`, or `cs3000`. Close spellings still match (`logic/search/SearchEngine.kt`, `ui/Screens.kt`). Filters include:

- All, Undergrad, or Grad. Undergrad is a course number below 5000. Grad is 5000 and above (`logic/search/SearchEngine.kt`, chips in `ui/Screens.kt`).
- Subject, NUpath, level (1000 through 9000), credits, campus, section type, honors, open seats, no prerequisites, no corequisites, in person or online, meeting days, and a start or end time (`logic/search/SearchEngine.kt`, `ui/Screens.kt`). "Open seats only" uses the waitlist-aware open check below.
- The no-prerequisite and no-corequisite switches apply only after that course's catalog details have been loaded.

A five-digit CRN is listed as its own card, and tapping it opens that section. Professor hits from the same search open a professor page. If the main search finds nothing and no filters are set, an on-device AndroidX AppSearch index is checked (`logic/search/CourseAppSearch.kt`, `ui/Screens.kt`). Recent searches are chips on the search screen.

Fall 2026 (`assets/catalog/term-202710.json.gz`) and Spring 2027 (`assets/catalog/term-202730.json.gz`) ship in the app. A fresh install opens the newest bundled term (`data/CatalogStore.kt`). Other Banner terms download on demand and stay under `files/catalogs/`. Home can refresh the open term from Banner (`ui/AppModel.kt`, `ui/Screens.kt`).

**Course page.** Title, credits, NUpath, description, prerequisite and corequisite trees, "prerequisite for", and sections (`ui/Screens.kt`). Refresh seats from Banner, or ask which older terms offered the course (`ui/AppModel.kt`). A CPS-only section, or a course whose loaded sections are CPS-only, shows "Restricted to CPS (College of Professional Studies) students". A mix shows "Some sections are CPS only" (`core/model/SectionRestriction.kt`, `ui/Screens.kt`).

**Seats.** A section with a waitlist queue is not open, even when Banner still reports seats remaining. Freed seats are treated as going to the waitlist first (`core/model/Models.kt`). The section line shows Open, Waitlist, or Full, the seat count, and "Waitlist 7/10" (or "Waitlist 7" when the capacity is unknown). When a queued section still has seats listed, the status word is omitted because the waitlist line already says so (`ui/Screens.kt`).

**Schedule.** Named plans for the open term, a week grid, and online or TBA sections listed under the grid (`ui/Screens.kt`). Overlapping meetings get a conflict dot and an error-colored border. The spoken description of that block says it conflicts with another class (`logic/schedule/CalendarEvent.kt`, `logic/schedule/CalendarLayout.kt`, `ui/Screens.kt`). A variable-credit row has a stepper. The choice is a whole number inside Banner's low and high (`core/model/VariableCredits.kt`, `ui/Screens.kt`).

Presets cover morning person, night owl, long weekends, and compact days (`logic/schedule/Schedule.kt`). A short description of the week ("no class after 4pm", "Fridays off", "in person") is parsed on the device (`logic/nl/PreferencesParser.kt`, `ui/Screens.kt`). Generate builds non-overlapping combinations, scores them, and shows reasons. When nothing fits, the screen lists blockers, conflicts, and move suggestions (`logic/schedule/ScheduleEngine.kt`, `ui/Screens.kt`). A matching semester on a degree plan can fill the schedule (`ui/AppModel.kt`).

**Calendar export.** Share an `.ics` file with one event per weekly meeting and each final, or insert those events through `CalendarContract` (calendar permission). Also share a PNG of the schedule and copy CRNs (`logic/schedule/ScheduleExport.kt`, `ui/AppModel.kt`, `ui/Screens.kt`).

**Degree plan.** Import a degree-audit "View Plan" PDF from the system picker or by opening the file in the app, edit completed and planned courses, and export a PDF in the same layout (`logic/degree/DegreePlan.kt`, `logic/degree/DegreePlanPdf.kt`, `AndroidManifest.xml`, `ui/Screens.kt`). The plan warns about a course added twice and about a missing prerequisite. Variable-credit rows use the same stepper. You can record that one course counts as another, and ask for suggestions when a number looks retired (`core/model/EquivalenceSuggester.kt`, `ui/Screens.kt`).

**Degree audit.** Load programs from `catalog.northeastern.edu` and run the audit on the device. The screen shows how many requirements are satisfied, each rule's status, note checks (including NUpath), unused courses, petition questions about course numbers, and a waive action (`logic/degree/ProgramCatalog.kt`, `logic/degree/DegreeAudit.kt`, `logic/degree/AuditEngine.kt`, `logic/degree/DegreePetitionQuestions.kt`, `ui/Screens.kt`). The same screen states the unofficial-app disclaimer.

**Offering history.** For courses on the plan, the app keeps about two years of Northeastern terms and can suggest moving a course that is not taught in the semester where it is planned (`logic/offering/OfferingHistoryRepository.kt`, `ui/Screens.kt`).

**Watchlist.** "Alert me if a seat opens" stores the CRN. WorkManager checks watched sections about every 15 minutes when background checks are on. If a seat opens, or a waitlist spot opens on a section that is still not open, it posts one notification for the first opening in that check (`logic/watchlist/Watchlist.kt`, `work/SeatWorker.kt`, `ui/Screens.kt`).

**Also on the device.**

- Free rooms for the next 30, 60, or 90 minutes, and a room's bookings (`logic/rooms/Rooms.kt`, `ui/Screens.kt`).
- Professors teaching this term, a teaching week, and the institutional email when one was kept. A button opens a Rate My Professors search for that name (`logic/professors/Professors.kt`, `ui/Screens.kt`).
- Glance widgets: Up next, and today's agenda (`widget/Widgets.kt`, `AndroidManifest.xml`).
- Links `termorganize://` and `neuclasssearch://` for search, schedule, the next class, a course, and a section. Launcher shortcuts for search, the schedule, and the next class (`MainActivity.kt`, `AndroidManifest.xml`, `app/src/main/res/xml/shortcuts.xml`).
- Settings for dynamic color, system or light or dark theme, weekends on the grid, and background seat checks. JSON export and import. The settings screen says iCloud sync from the Apple apps is not available on Android (`ui/Theme.kt`, `ui/Screens.kt`).
- Material 3 navigation for Home, Search, Schedule, Degree, and More. The suite uses a bar on a narrow window and a rail when the window is wider. At 840dp or more, search shows the list and the course together (`ui/Screens.kt`).

## Instructor emails and your data

Instructor email addresses are kept only when the domain is exactly `northeastern.edu` or `neu.edu`. The check is ASCII and case-insensitive, and the domain must be the whole domain: `husky.neu.edu`, `ece.northeastern.edu`, a trailing dot, a second `@`, and non-ASCII lookalikes are dropped. Several addresses in one field are split on spaces, commas, semicolons, and angle brackets. Only the exact-domain ones are kept. An obfuscated string that is not one of those addresses is dropped with the rest (`core/model/Models.kt`, `Instructor.institutionalEmail`). Banner results pass through that filter (`core/banner/Banner.kt`). A catalog is filtered again when it is loaded or saved (`data/CatalogStore.kt`).

The app does not create an account and does not upload plans, searches, or the watchlist to a Term Organize server. Those records are one JSON document in a Room database on the device, `term-organize.db` (`data/Store.kt`, `logic/degree/UserData.kt`). A degree-plan PDF can include a Student line and a Student ID line. The parser keeps both on the local plan (`logic/degree/DegreePlan.kt`). Exporting that plan as a PDF writes them back when a student name is present (`ui/AppModel.kt`). They stay in the on-device document.

Widget state is `widget-schedule.json` in app files (`data/Store.kt`). Downloaded catalogs are `files/catalogs/term-<code>.json` (`data/CatalogStore.kt`). Offering history is `files/course-info/offering-history.json`, and extra course info is `files/course-info/course-info.json` (`logic/offering/OfferingHistoryRepository.kt`, `logic/offering/CourseInfoRepository.kt`).

Live sections, the term list, seat checks, and offering history are read from `https://nubanner.neu.edu/StudentRegistrationSsb/ssb/` (`core/banner/BannerClient.kt`). Program requirements are read from `https://catalog.northeastern.edu/` when you tap Load catalog programs (`logic/degree/ProgramCatalog.kt`, `ui/AppModel.kt`).

## Build and run

From `app/build.gradle.kts` and `build.gradle.kts`:

- JDK 17 (`sourceCompatibility`, `targetCompatibility`, and Kotlin `jvmTarget`).
- Android SDK platform 36, build-tools 36.0.0 (`compileSdk`, `buildToolsVersion`).
- `minSdk` 26, `targetSdk` 36. The phone needs Android 8.0 or newer.
- Android Gradle Plugin 8.13.0, Kotlin 2.2.20. The wrapper is Gradle 8.14.3.

GitHub Actions (`.github/workflows/ci.yml`) uses Temurin 17 and installs `platforms;android-36` and `build-tools;36.0.0`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # your JDK 17 path

# local.properties, if the SDK is not already on ANDROID_HOME
echo "sdk.dir=$HOME/android-sdk" > local.properties

./gradlew :app:testDebugUnitTest
./gradlew assemble
```

`:app:testDebugUnitTest` runs the unit tests (JUnit and Robolectric). That is the test task CI runs. `./gradlew test` also runs `:app:testReleaseUnitTest`. Those release unit tests fail: Compose UI tests need `androidx.compose.ui:ui-test-manifest`, and that dependency is `debugImplementation` only (`app/build.gradle.kts`), so Robolectric cannot resolve the launcher activity in the release variant.

`assemble` writes:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

With no `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`, the release build is signed with the debug keystore. No signing keys are stored in this repository (`app/build.gradle.kts`).

Copy the debug APK to the phone. In Settings, Apps, Special app access, Install unknown apps, allow the file manager. Open the APK and install it. Android Studio can also run the `app` configuration.

CI runs `./gradlew :app:testDebugUnitTest :app:lint :app:assembleDebug`. The same lint task locally is `./gradlew :app:lint`.

Unit tests read the bundled catalogs because the test source set includes `src/main/assets` (`app/build.gradle.kts`). The loader accepts `catalog/term-<code>.json.gz` or `catalog/term-<code>.json`, and decompresses gzip bytes (`ui/AppModel.kt`).

`app/src/androidTest` has one instrumented test (`AppInstrumentedTest.kt`). It needs a device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

## License

[AGPL-3.0-only](LICENSE). If you distribute the app you must offer the corresponding source under the same license.
