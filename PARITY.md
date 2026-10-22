# Term Organize Android parity

This maps the SwiftUI app at [AlexmChadwick/Northeastern-Term-Organize](https://github.com/AlexmChadwick/Northeastern-Term-Organize) onto the Kotlin app in `app/`. Status is **ported**, **partial**, or **not ported**.

The Apple targets share one logic layer and two SwiftUI shells. Android has one adaptive Compose UI. Mac windowing, menus, and keyboard shortcuts are the tablet rail and the same screens.

## Feature checklist

| Swift feature | Android | Status |
| --- | --- | --- |
| Search: codes, titles, shorthand, typos, CRN, professor, subject | `logic/search/SearchEngine.kt`, Search screen | Ported. Search tests use the bundled Fall 2026 catalog. Spring 2027 is bundled too; a fresh install opens the newest bundled term. |
| Filters: subject, NUpath, level, credits, campus, type, honors, open seats, instruction, days, time | `SearchFilters`, filter sheet | Ported |
| Course page: prereq/coreq tree, prerequisite for, sections, seats, waitlist | Course screen, `Requisite` | Ported |
| Live section refresh | `BannerClient.refreshCourse`, `CatalogBuilder.applyCourseRefresh` | Ported when Banner answers. Otherwise the stored snapshot stays. |
| Every Banner term, on demand, with a disk cache | `CatalogStore`, `TermMenu`, `AppModel.openTerm`, Home term chips | Ported. Fall 2026 and Spring 2027 are bundled. A fresh install opens the newest bundled term; a download of another term does not change that, because the last picker choice is not stored. Banner’s term endpoint pages with `offset` as a page index (`BannerTermList`); the home chips are the 10 newest Northeastern terms, 6 CPS, and 6 Law from that full list, plus any term already saved on disk (including both bundled snapshots). Choosing one downloads sections, instructors (`getFacultyMeetingTimes`), subjects, campuses, and attributes, merges known course details, and saves `files/catalogs/term-<code>.json`. The fresher of the download and the bundle wins. |
| Schedule plans, primary plan, week calendar, conflicts, notices | Schedule screen, `ResolvedPlan`, `ScheduleNotices` | Ported. The schedule lists time conflicts, full sections, missing courses, a heavy credit load, unlisted CRNs, and sections left over from a removed course, and can forget those CRNs. A plan can be duplicated (`Name Copy`). Plans saved under another term are listed and open that term. |
| Constraint generator, sampler, weight table, ranking reasons, no-result suggestions | `ScheduleEngine`, `ScheduleScoring.Weights`, `SchedulePreferenceRules` | Ported. Exhaustive DFS, then the stratified sampler when the leaf, node, or time budget is hit. The weight table matches `ScheduleScoring.swift` (base 100, days, gaps, comfort, full sections, seats, days off, back-to-back, travel, lunch, bands, soft limits, format, campus, instructor). Reasons are up to 4 benefits and 2 caveats. |
| Natural-language preferences | `logic/nl/PreferencesParser.kt` | Ported as a deterministic parser. It does not call Apple Intelligence and it does not send the sentence off the device. It is always available. |
| Export `.ics` | `ScheduleExporter.events`, `calendarText` | Ported. One VEVENT per weekly meeting, plus each final. Recurrence is `RRULE` with `BYDAY` and `UNTIL`. |
| Android Calendar | `ScheduleExporter.insertAll` | Ported. With `WRITE_CALENDAR`, every weekly meeting and every final is inserted. Recurring rows use `DURATION` and `RRULE`, not `DTEND`. |
| Share image, copy CRNs | `AppModel` | Ported |
| Degree-plan PDF import (picker and Open with) | SAF `OpenDocument`, VIEW `application/pdf` | Ported |
| Edit plan, prerequisite checks, repeat checks, totals | Degree screen, `DegreePlanAnalysis` | Ported. Duplicate makes a copy named “name copy”, with new ids for the plan, semesters, and courses. |
| PDF export in the audit layout | `DegreePlanPdf.kt` | Ported. A4, Helvetica, the same headers, dash rules, and course lines. |
| Autofill from a degree plan or another schedule | `DegreePlanAutofill` | Ported |
| Degree audit, optimal rebalance, petition course numbers | `AuditEngine`, `DegreePetitionQuestions` | Ported. Substitutions, demand and contest, required and choice fill, rebalance (budget 2000, steps 0..3), `ensureBestMatching`, hour trim, note checks, and the petition course-number questions. The degree screen asks the question and stores the answer. |
| Offering history and semester-move suggestions | `OfferingHistory`, `OfferingHistoryRepository`, `DegreePlanReordering` | Ported. The degree screen loads two years of NEU terms (summers included), says when a course is usually taught, and offers moves, swaps, and a new semester when a course is not offered then. |
| Equivalences, renumbered courses, older terms | `CourseEquivalences`, `EquivalenceSuggester`, `CourseInfoStore`, `CourseTitleMatcher`, `checkOlderTerms` | Ported. Older terms query Banner. The degree screen’s Suggest equivalences button uses the same listed-together and similar-title rules as `EquivalenceSuggester` against the open catalog. Opening a degree plan calls `CourseInfoStore.load` and `loadAttributes`: prerequisites, Banner equivalents, and NUpath attributes for courses the open term does not list come from the disk cache, then from Banner. |
| Seat alerts | `SeatCheckWorker`, `WatchlistComparison` | Ported. Unique periodic work `seat-alerts`, 15 minutes, notification channel `seat-alerts`. |
| Free rooms | `RoomAvailability`, Rooms screen | Ported |
| Professors and teaching calendar | `ProfessorPage`, professor route | Ported, including a Rate My Professors search link |
| Widgets: Up next, daily agenda | Glance `UpNextWidget`, `AgendaWidget` | Ported |
| Deep links `termorganize://` and `neuclasssearch://` | `MainActivity.route` | Ported for search, schedule, next, course, and section |
| Siri / App Intents | `res/xml/shortcuts.xml` | Ported as static shortcuts: Search courses, My schedule, Next class |
| AppSearch course index | `logic/search/CourseAppSearch.kt` | Ported. Courses are indexed in AndroidX AppSearch local storage when a catalog opens, and published to platform storage when the device has it. In-app search still uses `SearchEngine`. AppSearch is consulted only when that search returns nothing. `SearchEngine` tests do not depend on the native Icing library. |
| Settings, disclaimer, license, export/import | Settings screen, Room, JSON | Ported. Dynamic color and dark mode are included. |
| iCloud / CloudKit sync | — | Not ported. There is no CloudKit on Android. Local Room storage plus JSON export/import is the replacement. `CloudSyncTests.swift` was not ported. |

## Swift file areas

| Swift area | Android | Status |
| --- | --- | --- |
| `Core/Model` (`Term`, `Course`, `DaySet`, meetings, catalog) | `core/model/Models.kt` | Ported |
| `Core/Model/Requisite` | `core/model/Requisite.kt` | Ported, including JSON `{s,n}` / `{and}` / `{or}` / `{test,score}` |
| `Core/Model/CourseEquivalences` | `core/model/Equivalences.kt` | Ported (union-find, order-independent roots) |
| `Core/Model/CourseTitleMatcher` | `core/model/TitleMatcher.kt` | Ported |
| `Core/Model/CourseInfo`, `CourseInfoStore`, equivalence suggestions | `CourseInfo`, `CourseInfoRepository`, `CourseInfoStore`, `EquivalenceSuggester` | Ported and tested. The degree screen starts the store’s Banner fetch for the plan’s courses, including NUpath attributes. |
| `Core/Banner/BannerClient` | `core/banner/BannerClient.kt` | Ported: paged terms, session, paged section download, faculty meeting times, subjects, campuses, attributes, offered-course probe, one-course refresh, catalog entry including the description (`fetchCourseInfo`), and per-course section search (`fetchSections`, used for attributes) |
| `Core/Banner/BannerParsers`, `CatalogBuilder` | `core/banner/Banner.kt` | Ported, including `merge` and `applyCourseRefresh` |
| `Core/Banner/CatalogArchive` (LZFSE) | `CatalogStore` JSON, gzip asset | Partial. The on-disk format is JSON. The Fall 2026 and Spring 2027 snapshots are the same catalogs, gzipped. LZFSE is not read. |
| `Services/CatalogRepository` | `data/CatalogStore.kt`, `AppModel.openTerm` | Ported for load, freshness, save, remote term list, and full-term sync |
| `Services/OfferingHistory` | `logic/offering/OfferingHistory.kt`, `OfferingHistoryRepository.kt` | Ported |
| `Core/ProgramCatalog` | `logic/degree/ProgramCatalog.kt` | Ported (edition URLs, program index, requirement tables). The live undergraduate index lists colleges, not degrees; `ProgramCatalogWalker` follows college, then department, pages. Course codes in those tables use a non-breaking space, which `HTMLText.plainText` already folds. |
| `Logic/Search` | `logic/search/SearchEngine.kt`, `CatalogIndex.kt` | Ported |
| `Logic/Schedule` generator, scoring, sampler, reasons, notices, blockers, ICS, calendar | `Schedule.kt`, `ScheduleEngine.kt`, `ScheduleScoring.kt`, `ScheduleSupport.kt`, `ScheduleExport.kt` | Ported |
| `Logic/Schedule/ScheduleAssistant` | `logic/nl/PreferencesParser.kt` | Ported without a language model |
| `Logic/DegreePlan` format, PDF, analysis, autofill, reordering, editing | `DegreePlan.kt`, `DegreePlanPdf.kt`, `DegreePlanReordering.kt`, `DegreePlanEditing.kt` | Ported |
| `Logic/DegreeAudit`, petition questions | `AuditEngine.kt`, `DegreePetitionQuestions.kt` | Ported |
| `Logic/DegreePlan` substitution editing, renumbering, note filters, substitute rows | `DegreePresentation.kt` | Ported |
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
| Spotlight | `CourseAppSearch` | Ported as AndroidX AppSearch. See the feature row. |

## Tests

Ported assertion-by-assertion, unless a row says otherwise:

| Swift suite | Android |
| --- | --- |
| `CoreModelTests`, `BannerParserTests`, `SearchEngineTests` | `LogicTests` |
| `ScheduleGeneratorTests`, `ScheduleLogicTests` | `LogicTests` |
| `SchedulePreferencesTests` weight table, filters, ranking, reasons, sampler, summary, presets, lenient JSON, draft grounding, blockers, survey context, reorder bridge | `SchedulePreferencesTests` (`ScheduleScoringTests`, `SchedulePreferencesFilterTests`, `SchedulePreferencesRankingTests`, `ScheduleReasonTests`, `SchedulePreferencesModelTests`, `ScheduleDraftTests`, `ScheduleBlockerTests`, `SchedulePreferencesContextTests`, `ScheduleReorderBridgeTests`, `SchedulePreferencesCatalogTests`) |
| `DegreeRequirementsTests`, `DegreeNUpathTests`, `DegreeAuditMatchingTests` | `DegreeAuditTests` |
| `DegreePlanTests` parse, format, PDF round-trip, prerequisites, autofill | `LogicTests` |
| `ShellSupportTests` watchlist and rooms, preferences sentence, widgets, active term | `LogicTests` |
| `PrerequisiteDataTests` offering history, repositories, reordering, equivalences, title matcher, suggester, course-info store | `RemainingLogicTests`, `PortedSuiteTests` |
| `DegreeSubstitutionPresentationTests` | `RemainingLogicTests` |
| Calendar: one event per meeting, `DURATION` + `RRULE`, finals, permission denied | `ScheduleCalendarTests`, `ScheduleCalendarPermissionTest` |
| Catalog freshness and `CatalogBuilder.merge` | `CatalogCacheTests` |
| `ScheduleSupportTests`, `SchedulePlanActionsTests` | `ScheduleSupportTests` |
| `CatalogSupportTests` day presets, browse text, popular subjects, professor notes, rooms wording | `CatalogAndDegreePresentationTests` |
| `DegreePlanMacSupportTests` reorder, summary, requirement listing | `CatalogAndDegreePresentationTests` |
| `DegreeNUpathTests` attribute repository | `CourseAttributeRepositoryTests` |
| Home disclaimer and search `CS 3000` | `AppUiTest` (Robolectric) and `AppInstrumentedTest` (emulator, UiAutomator) |

Not ported, or only partly covered:

- `CloudSyncTests` (CloudKit record merge, fingerprints, and account state). There is no CloudKit client. Local Room storage plus JSON export/import is the replacement. The merge rules were not reimplemented.
- `SchedulePreferencesTests` sentences that only an on-device Apple Intelligence model would fill. The deterministic parser covers the ported draft cases (times, bands, firmness, other languages, the block cap, and failure messages). It does not call a language model.
- `DegreePlanMacSupportTests` and `ShellSupportTests` cases that only assert Mac window and menu structure.
- `CatalogSupportTests` cases that only assert Apple file-presenter and LZFSE archive bytes. `CoreModelTests.catalogArchiveRoundTrip` is the same gap: the bundled catalog is gzip JSON, not an LZFSE archive.
- Resolved plans do not carry a `CalendarEvent` list. Calendar rows are built by `ScheduleExporter.events` (one event per weekly meeting, plus each final) and inserted by `insertAll`.
- `CourseAppSearch` on the JVM cannot load AndroidX AppSearch’s native Icing library. On a device the APK loads it. The unit test accepts a quiet empty result when the library is missing.

`CourseAppSearchTest` checks that a missing Icing native library does not throw and does not invent hits. The library loads from the APK on a device; the JVM unit-test runner has no Android `liblog`.

Live Banner is used by Refresh this term, Refresh seats, Older terms, offering history, course-catalog lookup, and section-attribute lookup. Unit tests do not require the network. If Banner is down, those actions report the error and leave the snapshot in place.

`connectedDebugAndroidTest` passed on an Android 14 AOSP ATD emulator (AVD `nto`, software CPU). The home test waits with UiAutomator: the catalog decode and the progress indicator keep the main looper busy, so the Compose test rule never sees an idle app on that emulator. Robolectric covers the same disclaimer and CS 3000 search.
