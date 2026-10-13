# Term Organize Android parity

This maps the SwiftUI app (git archive of `AlexmChadwick/Northeastern-Term-Organize` HEAD, extracted as the spec) onto the Kotlin app in `app/`. Status is **ported**, **partial**, or **not ported**.

The Apple targets (iPhone, iPad, Mac) share one logic layer and two SwiftUI shells. Android has one adaptive Compose UI. Mac windowing, menus, and keyboard shortcuts are covered by the tablet rail and the same screens, not by a second desktop shell.

## Feature checklist

| Swift feature | Android | Status |
| --- | --- | --- |
| Search: codes, titles, shorthand, typos, CRN, professor, subject | `logic/search/SearchEngine.kt`, Search screen | Ported. Tests use the bundled Fall 2026 catalog. |
| Filters: subject, NUpath, level, credits, campus, type, honors, open seats, instruction, days, time | `SearchFilters`, filter sheet | Ported |
| Course page: prereq/coreq tree, prerequisite for, sections, seats, waitlist | Course screen, `Requisite` | Ported |
| Live section refresh | `BannerClient.refreshCourse` | Ported when Banner answers. Otherwise the bundled snapshot stays. |
| Schedule plans, primary plan, week calendar, conflicts | Schedule screen, `Meeting.overlaps` | Ported |
| Constraint generator, survey presets, ranking, reasons, no-result suggestions | `ScheduleGenerator`, `SchedulePreset`, `SchedulePreferenceRules` | Ported. The search is a DFS with the same conflict rule (slot overlap and date windows). It does not include the Swift sampler or the full `ScheduleScoring.Weights` table. Generator tests from `ScheduleGeneratorTests.swift` pass, including CS 3000 + CS 3001 + MATH 2331. |
| Natural-language preferences | `logic/nl/PreferencesParser.kt` | Ported as a deterministic parser (`SchedulePreferencesDraft.applied` / `grounded`). It does not call Apple Intelligence, and it does not send the sentence off the device. It is always available. |
| Export `.ics`, system calendar, share image, copy CRNs | `ScheduleExporter`, `AppModel` | Ported. Calendar insert uses `ACTION_INSERT` for the first selected meeting. `.ics` carries the term’s meetings. |
| Degree-plan PDF import (picker and Open with) | SAF `OpenDocument`, VIEW `application/pdf` | Ported |
| Edit plan, prerequisite checks, repeat checks, totals | Degree screen, `DegreePlanAnalysis` | Ported |
| PDF export in the audit layout | `DegreePlanPdf.kt` | Ported. A4, Helvetica, the same headers, dash rules, and course lines. Round-trip test covers the sample export. |
| Autofill from a degree plan or another schedule | `DegreePlanAutofill` | Ported |
| Degree audit from the academic catalog | `ProgramCatalogParser`, `DegreeAudit` | Partial. HTML tables, required courses, choose-lists, ranges, general electives, NUpath (at most two areas), major hours, co-op, total hours, waivers, count-course substitutions, and equivalences are implemented. The Swift optimal rebalance (`ensureBestMatching`) and petition course-number questions are not. Greedy placement plus a fewest-credits choice is what runs. |
| Equivalences, renumbered courses, older terms | `CourseEquivalences`, `CourseTitleMatcher`, `checkOlderTerms` | Ported. Older terms query Banner for up to four earlier NEU terms. Title similarity suggests a current course when a completed title is missing from this catalog. |
| Seat alerts | `SeatCheckWorker`, `WatchlistComparison` | Ported. Unique periodic work `seat-alerts`, 15 minutes, notification channel `seat-alerts`. |
| Free rooms | `RoomAvailability`, Rooms screen | Ported |
| Professors and teaching calendar | `ProfessorPage`, professor route | Ported, including a Rate My Professors search link |
| Widgets: Up next, daily agenda | Glance `UpNextWidget`, `AgendaWidget` | Ported |
| Deep links `termorganize://` and `neuclasssearch://` | `MainActivity.route` | Ported for search, schedule, next, course, and section |
| Siri / App Intents | `res/xml/shortcuts.xml` | Ported as static shortcuts: Search courses, My schedule, Next class |
| System app search index | — | Not ported. In-app search and shortcuts are the Android equivalents that ship. AndroidX AppSearch is not indexed. |
| Settings, disclaimer, license, export/import | Settings screen, Room, JSON | Ported. Dynamic color and dark mode are included. |
| iCloud / CloudKit sync | — | Not ported. There is no CloudKit on Android. Local Room storage plus JSON export/import is the replacement. `CloudSyncTests.swift` was not ported. Device-only fields (student name and id, last seat check) simply stay in the local document. |

## Swift file areas

| Swift area | Android | Status |
| --- | --- | --- |
| `Core/Model` (`Term`, `Course`, `DaySet`, meetings, catalog) | `core/model/Models.kt` | Ported |
| `Core/Model/Requisite` | `core/model/Requisite.kt` | Ported, including JSON `{s,n}` / `{and}` / `{or}` / `{test,score}` |
| `Core/Model/CourseEquivalences` | `core/model/Equivalences.kt` | Ported (union-find, order-independent roots) |
| `Core/Model/CourseTitleMatcher` | `core/model/TitleMatcher.kt` | Ported |
| `Core/Banner/BannerClient` | `core/banner/BannerClient.kt` | Ported for terms, session, search, one-course refresh. `CatalogBuilder.merge` / `applyCourseRefresh` from the full-catalog scraper are not a background catalog rebuild. |
| `Core/Banner/BannerParsers`, `CatalogBuilder` | `core/banner/Banner.kt` | Ported |
| `Core/Banner/CatalogArchive` (LZFSE) | gzip JSON asset | Partial. Round-trip is JSON, not LZFSE. The Fall 2026 snapshot is the same catalog, recompressed. |
| `Core/ProgramCatalog` | `logic/degree/ProgramCatalog.kt` | Ported (edition URLs, program index, requirement tables) |
| `Logic/Search` | `logic/search/SearchEngine.kt`, `CatalogIndex.kt` | Ported |
| `Logic/Schedule` generator, filters, preferences, scoring, ICS | `logic/schedule/Schedule.kt` | Partial on scoring weights and sampling; behavior covered by the ported generator tests |
| `Logic/Schedule/ScheduleAssistant` | `logic/nl/PreferencesParser.kt` | Ported without a language model |
| `Logic/DegreePlan` format, PDF, analysis, autofill | `logic/degree/DegreePlan.kt`, `DegreePlanPdf.kt` | Ported |
| `Logic/DegreeAudit` | `logic/degree/DegreeAudit.kt` | Partial (see audit row) |
| `Logic/NUpath` | `NUpathMatching` inside `DegreeAudit.kt` | Ported (13 requirements, limited areas, co-op note) |
| `Logic/Watchlist` | `logic/watchlist/Watchlist.kt` | Ported |
| `Logic/Rooms` | `logic/rooms/Rooms.kt` | Ported |
| `Logic/Professors` | `logic/professors/Professors.kt` | Ported |
| `Logic/WidgetSchedule` | `logic/widget/WidgetSchedule.kt` | Ported |
| `Logic/UserData`, plans, preferences | `logic/degree/UserData.kt`, Room `data/Store.kt` | Ported locally |
| `Logic/CloudSync` | — | Not ported |
| `Logic/Settings/LegalText` | `res/values/strings.xml`, Settings, `assets/legal/LICENSE.txt` | Ported. Disclaimer text is the unofficial / not affiliated wording. |
| `DesignSystem` | `ui/Theme.kt` Material 3, dynamic color | Ported in Material 3 (teal and copper seed, paper background) rather than a pixel copy of the Swift tokens |
| iOS SwiftUI screens | `ui/Screens.kt` | Ported as phone and tablet adaptive screens |
| Mac SwiftUI windows | same screens, `NavigationSuiteScaffold` | Partial. No separate Mac menu bar or multi-window. |
| Widgets extension | `widget/Widgets.kt`, manifest receivers | Ported |
| App Intents | `res/xml/shortcuts.xml` | Ported as shortcuts |
| Tests under `NEUClassSearchTests` | `app/src/test/java/app/termorganize/LogicTests.kt`, `AppUiTest.kt` | Partial. Core, Banner parser, search, generator, degree-plan import/PDF, watchlist, rooms, widgets, active term, preferences, a small audit, and a Robolectric home/search flow are ported. Cloud sync, the 160-seed optimal-matching property test, petition questions, and Mac-only presentation tests are not. |

## Tests

`LogicTests` follows `CoreModelTests`, `BannerParserTests`, `SearchEngineTests`, `ScheduleGeneratorTests`, `ScheduleLogicTests`, `DegreePlanTests` (parse, format, PDF round-trip, prerequisites, autofill, primary plan), `ShellSupportTests` watchlist and room cases, and a preferences sentence. `AppUiTest` opens the home screen, checks the disclaimer, searches `CS 3000`, and expects Algorithms.

Live Banner is used by Refresh seats and Older terms. Unit tests do not require the network; they use the bundled catalog. If Banner is down, those buttons report the error and leave the snapshot in place.
