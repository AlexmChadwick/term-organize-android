// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Degree-audit assertions ported from the Swift test suites.

package app.termorganize

import app.termorganize.core.model.AppJson
import app.termorganize.core.model.CourseEquivalences
import app.termorganize.core.model.CourseKey
import app.termorganize.core.model.NUPath
import app.termorganize.logic.degree.CatalogEditions
import app.termorganize.logic.degree.CatalogProgram
import app.termorganize.logic.degree.CatalogYear
import app.termorganize.logic.degree.CourseNumberChoice
import app.termorganize.logic.degree.CourseNumberLimit
import app.termorganize.logic.degree.CourseOption
import app.termorganize.logic.degree.CourseRange
import app.termorganize.logic.degree.DegreeAudit
import app.termorganize.logic.degree.DegreeCourseAttributes
import app.termorganize.logic.degree.DegreePetitionQuestions
import app.termorganize.logic.degree.DegreePlan
import app.termorganize.logic.degree.DegreePlanAnalysis
import app.termorganize.logic.degree.DegreeRequirementText
import app.termorganize.logic.degree.DegreePlanCourse
import app.termorganize.logic.degree.DegreePlanTerm
import app.termorganize.logic.degree.NUpathMatching
import app.termorganize.logic.degree.NUpathRequirement
import app.termorganize.logic.degree.PlanSeason
import app.termorganize.logic.degree.ProgramCatalogParser
import app.termorganize.logic.degree.ProgramRequirements
import app.termorganize.logic.degree.RequirementGroup
import app.termorganize.logic.degree.RequirementReplacement
import app.termorganize.logic.degree.RequirementRule
import app.termorganize.logic.degree.RequirementSection
import app.termorganize.logic.degree.RequirementSubstitution
import app.termorganize.logic.degree.SubstitutionChange
import app.termorganize.logic.degree.UniversitywideRequirements
import app.termorganize.logic.degree.UserData
import app.termorganize.logic.degree.UserJson
import app.termorganize.logic.degree.likeRanges
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DegreeAuditTests {
    @Test fun parsesRequirementTables() {
        val requirements = requirements()
        assertEquals(52.0, requirements.totalHours!!, 0.0)
        assertEquals(
            listOf(
                "Major Requirements", "Supporting Courses", "Required General Electives", "Major GPA Requirement",
                "Program Requirement", "Concentration in Human-Centered Computing", "Software Concentration",
            ),
            requirements.sections.map { it.title },
        )
        assertEquals(listOf("Human-Centered Computing", "Software"), requirements.concentrations.map { it.concentrationName })
        assertFalse(requirements.allRules.any { CourseKey("CS", "9999") in it.listedCourses })
        val major = requirements.sections.first()
        assertEquals(listOf("Required Courses", "Security Course", "Khoury Elective Courses"), major.groups.map { it.title })
        val required = major.groups[0].rules
        assertEquals(listOf("CS 1800 and CS 1802", "CS 3000", "CS 4500 or CS 4530"), required.map { it.label })
        val first = required[0].kind as RequirementRule.Kind.Required
        assertEquals(listOf(listOf(CourseKey("CS", "1800"), CourseKey("CS", "1802"))), first.option.alternatives)
        assertEquals(4.0, required[2].hours!!, 0.0)
        val security = requiredChoose(major.groups[1].rules.first())
        assertEquals(1, security.count)
        assertEquals(2, security.options.size)
        assertTrue(security.ranges.isEmpty())
        val electives = requiredChoose(major.groups[2].rules.first())
        assertNull(electives.count)
        assertEquals(8.0, electives.hours!!, 0.0)
        assertEquals(listOf("CS 2500 or higher, except CS 5010", "DS 2500 to DS 4999 but not DS 4900"), electives.ranges.map { it.label })
        assertTrue(electives.ranges[0].contains(CourseKey("CS", "4973")))
        assertFalse(electives.ranges[0].contains(CourseKey("CS", "5010")))
        assertFalse(electives.ranges[0].contains(CourseKey("CS", "2100")))
        assertFalse(electives.ranges[1].contains(CourseKey("DS", "4900")))
        assertFalse(electives.ranges[1].contains(CourseKey("DS", "5110")))
        assertEquals(1, major.groups[2].notes.size)
        val science = requiredChoose(requirements.sections[1].groups.first().rules.first())
        assertEquals(2, science.count)
        assertEquals(listOf("BIOL 1111 and BIOL 1112", "MATH 2331"), science.options.map { it.label })
        val scienceRule = requirements.sections[1].groups.first().rules.first()
        assertEquals(listOf("Biology", "Mathematics"), scienceRule.notes)
        assertEquals(listOf("Biology", "Mathematics"), scienceRule.optionCategories)
        assertEquals("Science Requirement", requirements.titleOf(scienceRule.id))
        val general = requirements.sections[2].groups.first().rules.first().kind as RequirementRule.Kind.GeneralElectives
        assertEquals(8.0, general.hours, 0.0)
        assertEquals(listOf("Minimum 2.000 GPA required in the major."), requirements.sections[3].notes)
        assertEquals("Algorithms and Data", requirements.titleFor(CourseKey("CS", "3000")))
        assertEquals("Fundamentals of Software Engineering", requirements.titleFor(CourseKey("CS", "4530")))
        assertEquals("General Biology 1", requirements.titleFor(CourseKey("BIOL", "1111")))
        assertEquals("Lab for BIOL 1111", requirements.titleFor(CourseKey("BIOL", "1112")))
        assertEquals("Web Development", requirements.titleFor(CourseKey("CS", "4550")))
    }

    @Test fun ruleIDsAreStable() {
        val first = requirements().allRules.map { it.id }
        val second = requirements().allRules.map { it.id }
        assertEquals(first, second)
        assertEquals(first.size, first.toSet().size)
        assertTrue(first.contains("major-requirements/required-courses/cs-4500-or-cs-4530"))
    }

    @Test fun readsEditionsFromTheSite() {
        val page = """
            <header><span class="edition">Academic Catalog 2031-2032</span></header>
            <a href="/archive/2030-2031">2030-2031 Catalog</a>
            <a href="https://catalog.northeastern.edu/archive/2029-2030/">2029-2030 Catalog</a>
            <a href="https://catalog.northeastern.edu/pdf/2029-2030.pdf">PDF</a>
        """.trimIndent()
        assertEquals(CatalogYear(2031), ProgramCatalogParser.currentEdition(page))
        assertEquals(listOf(CatalogYear(2030), CatalogYear(2029)), ProgramCatalogParser.archivedEditions(page))
    }

    @Test fun editionURLsFollowTheSite() {
        val editions = CatalogEditions(CatalogYear(2031), listOf(CatalogYear(2030)))
        val path = "undergraduate/computer-information-science/computer-science/bscs/"
        assertEquals(
            "https://catalog.northeastern.edu/undergraduate/computer-information-science/computer-science/bscs/",
            editions.url(CatalogYear(2031), path),
        )
        assertEquals(
            "https://catalog.northeastern.edu/archive/2030-2031/undergraduate/computer-information-science/computer-science/bscs/",
            editions.url(CatalogYear(2030), path),
        )
        assertEquals(CatalogYear(2030), CatalogYear.parse("2030–2031"))
        assertEquals("2030–2031", CatalogYear.parse("2030-31").toString())
        val zone = ZoneId.of("America/New_York")
        val june = Instant.parse("2040-06-01T16:00:00Z").toEpochMilli()
        val october = Instant.parse("2040-10-01T16:00:00Z").toEpochMilli()
        assertEquals(CatalogYear(2039), CatalogYear.containing(june, zone))
        assertEquals(CatalogYear(2040), CatalogYear.containing(october, zone))
        assertEquals(CatalogYear(2016), CatalogEditions.estimated(october).archived.last())
    }

    @Test fun listsProgramsFromTheIndex() {
        val index = """
            <a href="/archive/2023-2024/undergraduate/computer-information-science/computer-science/bscs/">Computer Science, BSCS</a>
            <a href="/graduate/computer-information-science/computer-science/computer-science-ms/">Computer Science, MS (Boston)</a>
            <a href="/undergraduate/computer-information-science/computer-science/computer-science-minor/">Computer Science Minor</a>
            <a href="/undergraduate/about/">About, The University</a>
            <a href="#programs">Programs, AZ</a>
        """.trimIndent()
        val programs = ProgramCatalogParser.programs(index)
        assertEquals(listOf("Computer Science, BSCS", "Computer Science, MS (Boston)"), programs.map { it.title })
        assertEquals("undergraduate/computer-information-science/computer-science/bscs/", programs[0].path)
        assertEquals(CatalogProgram.Level.GRADUATE, programs[1].level)
        assertEquals("MS", programs[1].degree)
        assertEquals("Boston", programs[1].campus)
        assertEquals(listOf("BSCS"), ProgramCatalogParser.suggestedPrograms("CS-B-CSCI-BSCS", programs).map { it.degree })
    }

    @Test fun auditMatchesCoursesToRequirements() {
        val audit = DegreeAudit.run(plan(), requirements())
        assertFalse(audit.needsConcentration)
        assertEquals(DegreeAudit.Status.COMPLETE, status(audit, "CS 1800 and CS 1802"))
        assertEquals(DegreeAudit.Status.COMPLETE, status(audit, "CS 3000"))
        assertEquals(DegreeAudit.Status.MISSING, status(audit, "CS 4500 or CS 4530"))
        assertEquals(DegreeAudit.Status.COMPLETE, status(audit, "Complete one of the following:"))
        assertEquals(DegreeAudit.Status.COMPLETE, status(audit, "CS 2484"))
        val hcc = audit.ruleResults.first { it.rule.label == "Complete two of the following not already taken:" }
        assertEquals(DegreeAudit.Status.PARTIAL, hcc.status)
        assertEquals(1, hcc.remainingCount)
        val electives = audit.ruleResults.first { it.rule.label.startsWith("Complete 8 semester hours of CS") }
        assertEquals(DegreeAudit.Status.COMPLETE, electives.status)
        assertEquals(setOf("CS 4973", "CS 4535"), electives.used.map { it.key.description }.toSet())
        assertEquals(DegreeAudit.Status.MISSING, status(audit, "Complete two courses (and any required labs) from the following science categories:"))
        assertFalse(audit.sections.any { it.section.title == "Software Concentration" })
        assertEquals(34.0, audit.completedHours, 0.0)
        assertEquals(16.0, audit.plannedHours, 0.0)
        assertEquals(52.0, audit.requiredHours!!, 0.0)
    }

    @Test fun substitutionsAndPetitions() {
        val requirements = requirements()
        val hccRule = requirements.allRules.first { it.label == "Complete two of the following not already taken:" }
        val softwareEngineering = requirements.allRules.first { it.label == "CS 4500 or CS 4530" }
        val science = requirements.allRules.first { it.label.startsWith("Complete two courses") }
        val electives = requirements.allRules.first { it.label.startsWith("Complete 8 semester hours of CS") }
        val softwareOnly = requirements.allRules.first { it.label == "CS 4400" }
        val plan = plan().copy(
            substitutions = listOf(
                RequirementSubstitution(ruleID = hccRule.id, ruleTitle = "HCI electives", change = SubstitutionChange.CountCourse(CourseKey("CS", "4973"))),
                RequirementSubstitution(ruleID = softwareEngineering.id, ruleTitle = "Software engineering", change = SubstitutionChange.CountCourse(CourseKey("CS", "4535"))),
                RequirementSubstitution(
                    ruleID = science.id,
                    ruleTitle = "Science Requirement",
                    change = SubstitutionChange.Replace(RequirementReplacement(count = 2, likeRuleID = electives.id, likeRuleTitle = "Khoury Elective Courses")),
                    note = "Petition approved",
                ),
                RequirementSubstitution(ruleID = softwareOnly.id, ruleTitle = "CS 4400", change = SubstitutionChange.Waive),
                RequirementSubstitution(ruleID = electives.id, ruleTitle = "Electives", change = SubstitutionChange.CountCourse(CourseKey("CS", "4999"))),
            ),
        )
        val audit = DegreeAudit.run(plan, requirements)
        val hcc = audit.result(hccRule.id)!!
        assertEquals(DegreeAudit.Status.COMPLETE, hcc.status)
        assertTrue(hcc.used.any { it.key.description == "CS 4973" && it.bySubstitution })
        val engineering = audit.result(softwareEngineering.id)!!
        assertEquals(DegreeAudit.Status.COMPLETE, engineering.status)
        assertEquals(listOf("CS 4535"), engineering.used.map { it.key.description })
        val electiveResult = audit.result(electives.id)!!
        assertEquals(DegreeAudit.Status.PLANNED, electiveResult.status)
        assertEquals(setOf("DS 3000", "CS 4410"), electiveResult.used.map { it.key.description }.toSet())
        val scienceResult = audit.result(science.id)!!
        assertEquals(DegreeAudit.Status.PLANNED, scienceResult.status)
        assertEquals(setOf("CS 3800", "CS 4100"), scienceResult.used.map { it.key.description }.toSet())
        assertEquals("Petition approved", scienceResult.substitutions.first().note)
        assertEquals(setOf("CS 4400", "Electives"), audit.unmatchedSubstitutions.map { it.ruleTitle }.toSet())
        val counted = audit.ruleResults.flatMap { result -> result.used.map { it.key } }
        assertEquals(counted.size, counted.toSet().size)
    }

    @Test fun shortfallLandsOnTheHoursRequirement() {
        val requirements = requirements()
        val science = requirements.allRules.first { it.label.startsWith("Complete two courses") }
        val electives = requirements.allRules.first { it.label.startsWith("Complete 8 semester hours of CS") }
        val spring = DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf(course("CS 3386"), course("CS 4050"), course("CS 2963", 1.0)))
        val plan = DegreePlan(
            name = "Short",
            completed = listOf(course("CS 1800"), course("CS 1802", 1.0), course("CS 3000"), course("CY 2550")),
            terms = listOf(spring),
            concentrationID = "concentration-in-human-centered-computing",
            substitutions = listOf(
                RequirementSubstitution(
                    ruleID = science.id,
                    ruleTitle = "Science Requirement",
                    change = SubstitutionChange.Replace(RequirementReplacement(count = 2, likeRuleID = electives.id, likeRuleTitle = "Khoury Elective Courses")),
                ),
            ),
        )
        val audit = DegreeAudit.run(plan, requirements)
        val scienceResult = audit.result(science.id)!!
        assertEquals(DegreeAudit.Status.PLANNED, scienceResult.status)
        assertEquals(setOf("CS 3386", "CS 4050"), scienceResult.used.map { it.key.description }.toSet())
        val electiveResult = audit.result(electives.id)!!
        assertEquals(DegreeAudit.Status.PARTIAL, electiveResult.status)
        assertEquals(listOf("CS 2963"), electiveResult.used.map { it.key.description })
        assertEquals(7.0, electiveResult.remainingHours!!, 0.0)
    }

    @Test fun rebalancesToMeetShortRequirements() {
        val page = """
            <div id="programrequirementstextcontainer" class="tab_content">
            <h2>Major Requirements</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even areaheader firstrow"><td colspan="2"><span class="courselistcomment areaheader">Computing and Social Issues</span></td><td class="hourscol"></td></tr>
            <tr class="odd"><td colspan="2"><span class="courselistcomment">Complete one of the following:</span></td><td class="hourscol">4</td></tr>
            <tr class="even"><td class="codecol"><a class="bubblelink code">CY&#160;4170</a></td><td>The Law, Ethics, and Policy of Data and Digital Technologies</td><td class="hourscol"></td></tr>
            <tr class="odd"><td class="codecol"><a class="bubblelink code">HIST&#160;2220</a></td><td>History of Technology</td><td class="hourscol"></td></tr>
            <tr class="even areaheader"><td colspan="2"><span class="courselistcomment areaheader">Humanities</span></td><td class="hourscol"></td></tr>
            <tr class="odd"><td colspan="2"><span class="courselistcomment">Complete one of the following:</span></td><td class="hourscol">4</td></tr>
            <tr class="even"><td class="codecol"><a class="bubblelink code">HIST&#160;2220</a></td><td>History of Technology</td><td class="hourscol"></td></tr>
            <tr class="odd"><td class="codecol"><a class="bubblelink code">PHIL&#160;1145</a></td><td>Technology and Human Values</td><td class="hourscol"></td></tr>
            <tr class="even areaheader"><td colspan="2"><span class="courselistcomment areaheader">Khoury Elective Courses</span></td><td class="hourscol"></td></tr>
            <tr class="odd"><td colspan="2"><span class="courselistcomment">Complete 8 semester hours of CS, CY, DS, or IS classes that are not already required. Choose courses within the following ranges:</span></td><td class="hourscol">8</td></tr>
            <tr class="even"><td colspan="2"><div style="margin-left:20px;" class="blockindent"><span class="courselistcomment commentindent"><a class="bubblelink code">CS&#160;2500</a> or higher</span></div></td><td class="hourscol"></td></tr>
            <tr class="odd lastrow"><td colspan="2"><div style="margin-left:20px;" class="blockindent"><span class="courselistcomment commentindent"><a class="bubblelink code">CY&#160;2000</a> or higher</span></div></td><td class="hourscol"></td></tr>
            </tbody></table>
            <h2>Required General Electives</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even lastrow firstrow"><td colspan="2"><span class="courselistcomment">Complete 4 semester hours of general electives.</span></td><td class="hourscol">4</td></tr>
            </tbody></table>
            </div>
        """.trimIndent()
        val requirements = ProgramCatalogParser.requirements(page, CatalogYear(2024), program)!!
        val rules = requirements.allRules
        assertEquals(4, rules.size)
        val socialIssues = rules[0]
        val humanities = rules[1]
        val electives = rules[2]
        fun audit(completed: List<String>, planned: List<String>): DegreeAudit.Result {
            val plan = DegreePlan(
                name = "Plan",
                completed = completed.map { course(it) },
                terms = listOf(DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = planned.map { course(it) })),
            )
            return DegreeAudit.run(plan, requirements)
        }
        fun courses(audit: DegreeAudit.Result, rule: RequirementRule) = audit.result(rule.id)?.used.orEmpty().map { it.key.description }.toSet()
        val swap = audit(listOf("CY 4170", "CS 3200", "PHIL 1145"), listOf("HIST 2220", "MKTG 4510"))
        assertEquals(swap.totalCount, swap.satisfiedCount)
        assertEquals(setOf("CS 3200", "CY 4170"), courses(swap, electives))
        assertEquals(setOf("HIST 2220"), courses(swap, socialIssues))
        assertEquals(setOf("PHIL 1145"), courses(swap, humanities))
        assertEquals(listOf("MKTG 4510"), swap.generalElectives.map { it.key.description })
        val chain = audit(listOf("CY 4170", "CS 3200", "HIST 2220"), listOf("PHIL 1145", "MKTG 4510"))
        assertEquals(chain.totalCount, chain.satisfiedCount)
        assertEquals(setOf("CS 3200", "CY 4170"), courses(chain, electives))
        assertEquals(DegreeAudit.Status.COMPLETE, chain.result(electives.id)?.status)
        assertEquals(setOf("HIST 2220"), courses(chain, socialIssues))
        assertEquals(setOf("PHIL 1145"), courses(chain, humanities))
        val counted = chain.ruleResults.flatMap { it.used.map { use -> use.key } }
        assertEquals(counted.size, counted.toSet().size)
        val short = audit(listOf("CY 4170", "CS 3200"), listOf("MKTG 4510"))
        assertEquals(DegreeAudit.Status.COMPLETE, short.result(socialIssues.id)?.status)
        assertEquals(4.0, short.result(electives.id)?.remainingHours!!, 0.0)
    }

    @Test fun petitionsAskWhetherCourseNumberMinimumsApply() {
        val requirements = requirements()
        val electives = requirements.allRules.first { it.label.startsWith("Complete 8 semester hours of CS") }
        val science = requirements.allRules.first { it.label.startsWith("Complete two courses") }
        fun petition(limit: CourseNumberLimit?) = RequirementSubstitution(
            ruleID = science.id,
            ruleTitle = "Science Requirement",
            change = SubstitutionChange.Replace(
                RequirementReplacement(count = 2, likeRuleID = electives.id, likeRuleTitle = "Khoury Elective Courses", courseNumbers = limit),
            ),
        )
        val base = DegreePlan(
            name = "Petition",
            completed = listOf(
                course("CS 1800"), course("CS 1802", 1.0), course("CS 3000"), course("CS 4500"), course("CY 2550"),
                course("CS 2386", 4.0, "Game Programming 1"), course("CS 2484"), course("CS 4520"), course("CS 4550"),
            ),
            terms = listOf(
                DegreePlanTerm(
                    season = PlanSeason.SPRING,
                    year = 2027,
                    courses = listOf(course("CS 3386", 4.0, "Game Programming 2"), course("CS 3200"), course("CS 4100"), course("MKTG 2602"), course("PHIL 1102")),
                ),
            ),
            concentrationID = "concentration-in-human-centered-computing",
        )
        val unansweredPlan = base.copy(substitutions = listOf(petition(null)))
        val unanswered = DegreeAudit.run(unansweredPlan, requirements)
        assertEquals(DegreeAudit.Status.PARTIAL, unanswered.result(electives.id)?.status)
        val questions = DegreePetitionQuestions.questions(unansweredPlan, requirements, audit = unanswered)
        assertEquals(1, questions.size)
        val question = questions.first()
        assertEquals(listOf(CourseKey("CS", "2386")), question.courses)
        assertEquals("Does your Science Requirement petition accept any CS or DS course?", question.question)
        assertTrue(question.explanation.contains("start at CS 2500 or DS 2500"))
        assertTrue(question.explanation.contains("CS 2386 counts"))
        val anyPlan = base.copy(substitutions = listOf(petition(CourseNumberLimit.AnyNumber)))
        val anyNumber = DegreeAudit.run(anyPlan, requirements)
        assertTrue(anyNumber.result(science.id)?.used.orEmpty().any { it.key.description == "CS 2386" })
        assertEquals(DegreeAudit.Status.PLANNED, anyNumber.result(electives.id)?.status)
        assertTrue(DegreePetitionQuestions.questions(anyPlan, requirements).isEmpty())
        val ranges = (anyNumber.result(science.id)?.effectiveKind as RequirementRule.Kind.Choose).ranges
        assertEquals(listOf("Any CS course, except CS 5010", "DS courses up to DS 4999, except DS 4900"), ranges.map { it.label })
        assertEquals(DegreeAudit.Status.PLANNED, DegreeAudit.run(base.copy(substitutions = listOf(petition(CourseNumberLimit.AtLeast(2000)))), requirements).result(electives.id)?.status)
        assertEquals(DegreeAudit.Status.PARTIAL, DegreeAudit.run(base.copy(substitutions = listOf(petition(CourseNumberLimit.AtLeast(3000)))), requirements).result(electives.id)?.status)
        val samePlan = base.copy(substitutions = listOf(petition(CourseNumberLimit.SameAsRequirement)))
        assertTrue(DegreePetitionQuestions.questions(samePlan, requirements).isEmpty())
        val same = DegreeAudit.run(samePlan, requirements)
        val miss = same.nearMisses(electives.id).first()
        assertEquals("CS 2386", miss.course.key.description)
        assertEquals("Below CS 2500", miss.reason)
        val decoded = AppJson.user.decodeFromString(
            RequirementReplacement.serializer(),
            """{"count":2,"likeRuleID":"x","likeRuleTitle":"Khoury Elective Courses","courses":[]}""",
        )
        assertNull(decoded.courseNumbers)
        assertEquals(
            "1 course like Khoury Elective Courses, any course number",
            RequirementReplacement(count = 1, likeRuleTitle = "Khoury Elective Courses", courseNumbers = CourseNumberLimit.AnyNumber).summary,
        )
        assertEquals(CourseNumberChoice.ANY_NUMBER, CourseNumberChoice.from(CourseNumberLimit.AnyNumber))
        assertEquals(2000, CourseNumberChoice.suggestedMinimum(ranges, null))
    }

    @Test fun topicsCoursesCountEachTime() {
        val requirements = requirements()
        val electives = requirements.allRules.first { it.label.startsWith("Complete 8 semester hours of CS") }
        val plan = DegreePlan(
            name = "Topics",
            completed = listOf(
                course("CS 1800"), course("CS 1802", 1.0), course("CS 3000"), course("CS 4500"), course("CY 2550"),
                course("CS 4973", 4.0, "Topics in Computer Science"), course("MKTG 2602", 4.0, "Marketing Management"),
            ),
            terms = listOf(
                DegreePlanTerm(
                    season = PlanSeason.SPRING,
                    year = 2027,
                    courses = listOf(course("CS 4973", 4.0, "Topics in Computer Science"), course("MKTG 2602", 4.0, "Marketing Management")),
                ),
            ),
        )
        val audit = DegreeAudit.run(plan, requirements)
        val result = audit.result(electives.id)!!
        assertEquals(DegreeAudit.Status.PLANNED, result.status)
        assertEquals(listOf("CS 4973", "CS 4973 (2)"), result.used.map { it.id }.sorted())
        val counted = audit.ruleResults.flatMap { it.used } + audit.unused
        assertEquals(1, counted.count { it.key.description == "MKTG 2602" })
        assertEquals(counted.size, counted.map { it.id }.toSet().size)
        assertEquals(setOf(CourseKey("MKTG", "2602")), DegreePlanAnalysis.duplicateKeys(plan))
    }

    @Test fun requiredAlternativesMakeRoom() {
        val page = """
            <div id="programrequirementstextcontainer" class="tab_content">
            <h2>Major Requirements</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even areaheader firstrow"><td colspan="2"><span class="courselistcomment areaheader">Software Engineering</span></td><td class="hourscol"></td></tr>
            <tr class="odd"><td class="codecol"><a class="bubblelink code">CS&#160;4500</a></td><td>Software Development</td><td class="hourscol">4</td></tr>
            <tr class="orclass even"><td class="codecol orclass">or <a class="bubblelink code">CS&#160;4530</a></td><td colspan="2">Fundamentals of Software Engineering</td></tr>
            <tr class="odd areaheader"><td colspan="2"><span class="courselistcomment areaheader">Concentration Courses</span></td><td class="hourscol"></td></tr>
            <tr class="even"><td colspan="2"><span class="courselistcomment">Complete two of the following:</span></td><td class="hourscol">8</td></tr>
            <tr class="odd"><td class="codecol"><a class="bubblelink code">CS&#160;4530</a></td><td>Fundamentals of Software Engineering</td><td class="hourscol"></td></tr>
            <tr class="even lastrow"><td class="codecol"><a class="bubblelink code">CS&#160;4550</a></td><td>Web Development</td><td class="hourscol"></td></tr>
            </tbody></table>
            </div>
        """.trimIndent()
        val requirements = ProgramCatalogParser.requirements(page, CatalogYear(2024), program)!!
        val rules = requirements.allRules
        assertEquals(2, rules.size)
        val plan = DegreePlan(
            name = "Plan",
            completed = listOf(course("CS 4530"), course("CS 4550")),
            terms = listOf(DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf(course("CS 4500")))),
        )
        val audit = DegreeAudit.run(plan, requirements)
        assertEquals(2, audit.satisfiedCount)
        assertEquals(listOf("CS 4500"), audit.result(rules[0].id)?.used?.map { it.key.description })
        assertEquals(setOf("CS 4530", "CS 4550"), audit.result(rules[1].id)?.used.orEmpty().map { it.key.description }.toSet())
    }

    @Test fun substitutionsFollowRequirementsAcrossCatalogYears() {
        val requirements = requirements()
        val science = requirements.allRules.first { it.label.startsWith("Complete two courses") }
        val electives = requirements.allRules.first { it.label.startsWith("Complete 8 semester hours of CS") }
        val kept = RequirementSubstitution(
            ruleID = "supporting-courses/science-requirement/complete-any-two-courses-from-last-year",
            ruleTitle = "Science Requirement",
            change = SubstitutionChange.Replace(
                RequirementReplacement(
                    count = 2,
                    likeRuleID = "major-requirements/khoury-elective-courses/older-wording",
                    likeRuleTitle = "Khoury Elective Courses",
                    courseNumbers = CourseNumberLimit.SameAsRequirement,
                ),
            ),
        )
        val plan = plan().copy(
            substitutions = listOf(
                kept,
                RequirementSubstitution(ruleID = "gone", ruleTitle = "A requirement this year doesn't have", change = SubstitutionChange.Waive),
            ),
        )
        val audit = DegreeAudit.run(plan, requirements)
        val result = audit.result(science.id)!!
        assertEquals(1, result.substitutions.size)
        assertEquals(DegreeAudit.Status.COMPLETE, result.status)
        assertEquals(setOf("CS 4973", "CS 4535"), result.used.map { it.key.description }.toSet())
        assertEquals(science.id, audit.placements[kept.id])
        assertEquals(listOf("A requirement this year doesn't have"), audit.unmatchedSubstitutions.map { it.ruleTitle })
        val replacement = (plan.substitutions[0].change as SubstitutionChange.Replace).replacement
        assertEquals(2, replacement.likeRanges(requirements).size)
        assertEquals(DegreeAudit.Status.PLANNED, audit.result(electives.id)?.status)
    }

    @Test fun hourRequirementsKeepOnlyWhatTheyNeed() {
        val requirements = requirements()
        val electives = requirements.allRules.first { it.label.startsWith("Complete 8 semester hours of CS") }
        val plan = DegreePlan(
            name = "Hours",
            completed = listOf(
                course("CS 1800"), course("CS 1802", 1.0), course("CS 3000"), course("CS 4500"), course("CY 2550"),
                course("CS 4100"), course("MATH 1341"), course("PHIL 1102"),
            ),
            terms = listOf(
                DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = listOf(course("CS 2963", 1.0, "Topics"))),
                DegreePlanTerm(season = PlanSeason.SUMMER_B, year = 2027, courses = listOf(course("CS 3200"))),
            ),
        )
        val audit = DegreeAudit.run(plan, requirements)
        val result = audit.result(electives.id)!!
        assertEquals(DegreeAudit.Status.PLANNED, result.status)
        assertEquals(setOf("CS 4100", "CS 3200"), result.used.map { it.key.description }.toSet())
        assertEquals(8.0, result.used.sumOf { it.credits }, 0.0)
        assertEquals(setOf("MATH 1341", "PHIL 1102"), audit.generalElectives.map { it.key.description }.toSet())
        assertEquals(listOf("CS 2963"), audit.unused.map { it.key.description })
        val uses = listOf(5.0 to true, 4.0 to false, 4.0 to false).mapIndexed { index, item ->
            DegreeAudit.CourseUse(CourseKey("CS", "${4000 + index}"), "A", item.first, if (item.second) null else "Spring 2027", index)
        }
        assertEquals(setOf("CS 4001", "CS 4002"), DegreeAudit.fewestCreditsChoice(uses, 8.0))
    }

    @Test fun checksHoursInTheMajorAndCoop() {
        val page = """
            <div id="programrequirementstextcontainer" class="tab_content">
            <h2>Major Requirements</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even firstrow"><td class="codecol"><a class="bubblelink code">CS&#160;3000</a></td><td>Algorithms and Data</td><td class="hourscol">4</td></tr>
            <tr class="odd"><td colspan="2"><span class="courselistcomment">Complete 8 semester hours of CS classes. Choose courses within the following ranges:</span></td><td class="hourscol">8</td></tr>
            <tr class="even lastrow"><td colspan="2"><div style="margin-left:20px;" class="blockindent"><span class="courselistcomment commentindent"><a class="bubblelink code">CS&#160;2500</a> or higher</span></div></td><td class="hourscol"></td></tr>
            </tbody></table>
            <h2>Writing Requirement</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even firstrow lastrow"><td class="codecol"><a class="bubblelink code">ENGW&#160;1111</a></td><td>First-Year Writing</td><td class="hourscol">4</td></tr>
            </tbody></table>
            <h2>Required General Electives</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even lastrow firstrow"><td colspan="2"><span class="courselistcomment">Complete 4 semester hours of general electives.</span></td><td class="hourscol">4</td></tr>
            </tbody></table>
            <h2>GPA Requirement</h2>
            <p>Minimum 2.000 GPA required in the major.</p>
            <h2>Credit Requirement</h2>
            <p>Complete 16 semester hours in the major.</p>
            <h2>NUpath Requirements Satisfied</h2>
            <p>Integrating Knowledge and Skills Through Experience is satisfied through co-op.</p>
            </div>
        """.trimIndent()
        val requirements = ProgramCatalogParser.requirements(page, CatalogYear(2024), program)!!
        fun audit(completed: List<String>, planned: List<String> = emptyList()) = DegreeAudit.run(
            DegreePlan(
                name = "Checks",
                completed = completed.map { code -> if (code == "COOP 3946") course(code, 0.0, "Co-op Work Experience - HT") else course(code) },
                terms = listOf(DegreePlanTerm(season = PlanSeason.FALL, year = 2027, courses = planned.map { course(it) })),
            ),
            requirements,
        )
        val done = audit(listOf("CS 3000", "CS 3200", "CS 3500", "CS 4100", "ENGW 1111", "MKTG 2602", "COOP 3946", "CS 1210"))
        assertEquals(2, done.noteChecks.size)
        val hours = done.noteChecks.first { it.kind is DegreeAudit.NoteCheck.Kind.MajorHours }
        assertEquals(DegreeAudit.Status.COMPLETE, hours.status)
        assertEquals("16 of 16 semester hours", hours.detail)
        val coop = done.noteChecks.first { it.kind is DegreeAudit.NoteCheck.Kind.Coop }
        assertEquals(DegreeAudit.Status.COMPLETE, coop.status)
        assertEquals("COOP 3946 completed", coop.detail)
        assertEquals(done.totalCount, done.satisfiedCount)
        val short = audit(listOf("CS 3000", "CS 3200", "CS 1210"), listOf("CS 3500"))
        val shortHours = short.noteChecks.first { it.kind is DegreeAudit.NoteCheck.Kind.MajorHours }
        // 4 (CS 3000) + 4 (CS 3200) earned, 4 (CS 3500) planned. Not 12 of 16 with the 4 planned again.
        assertEquals(DegreeAudit.Status.PARTIAL, shortHours.status)
        assertEquals("8 of 16 semester hours · 4 planned", shortHours.detail)
        assertEquals("No co-op in the plan", short.noteChecks.first { it.kind is DegreeAudit.NoteCheck.Kind.Coop }.detail)
        assertTrue(short.satisfiedCount < short.totalCount)
    }

    @Test fun waiversAndConcentrationChoice() {
        val requirements = requirements()
        val security = requirements.allRules.first { it.label == "Complete one of the following:" }
        val plan = plan().copy(
            substitutions = listOf(RequirementSubstitution(ruleID = security.id, ruleTitle = "Security Course", change = SubstitutionChange.Waive)),
            concentrationID = null,
        )
        val audit = DegreeAudit.run(plan, requirements)
        assertEquals(DegreeAudit.Status.WAIVED, audit.result(security.id)?.status)
        assertTrue(audit.needsConcentration)
        assertFalse(audit.sections.any { it.section.isConcentration })
        assertTrue(audit.ruleCounting(CourseKey("CY", "2550"))?.rule?.id != security.id)
    }

    @Test fun userDataSkipsUnreadableEntries() {
        val data = UserData(degreePlans = listOf(plan(), DegreePlan(name = "Second")), searchHistory = listOf("cs 3000", "orgo"))
        val encoded = AppJson.user.parseToJsonElement(UserJson.encode(data)).jsonObject.toMutableMap()
        val plans = encoded.getValue("degreePlans").jsonArray.toMutableList()
        val broken = (plans[0] as JsonObject).toMutableMap()
        broken["completed"] = JsonPrimitive("not a list")
        plans[0] = JsonObject(broken)
        encoded["degreePlans"] = JsonArray(plans)
        encoded["searchHistory"] = JsonArray(listOf(JsonPrimitive("cs 3000"), JsonPrimitive(42), JsonPrimitive("orgo")))
        val decoded = UserJson.decodeElement(JsonObject(encoded))
        assertEquals(listOf("Second"), decoded.degreePlans.map { it.name })
        assertEquals(listOf("cs 3000", "orgo"), decoded.searchHistory)
    }

    @Test fun olderSavedPlansStillDecode() {
        val plan = plan().copy(substitutions = listOf(RequirementSubstitution(ruleID = "x", ruleTitle = "X", change = SubstitutionChange.Waive)))
        val json = AppJson.user.encodeToJsonElement(DegreePlan.serializer(), plan).jsonObject.toMutableMap()
        for (key in listOf("substitutions", "catalogYear", "catalogProgramPath", "catalogProgramTitle", "concentrationID")) json.remove(key)
        val decoded = AppJson.user.decodeFromJsonElement(DegreePlan.serializer(), JsonObject(json))
        assertEquals("Plan", decoded.name)
        assertEquals(plan.completed.size, decoded.completed.size)
        assertTrue(decoded.substitutions.isEmpty())
        assertNull(decoded.catalogYear)
        val roundTrip = AppJson.user.decodeFromString(DegreePlan.serializer(), AppJson.user.encodeToString(DegreePlan.serializer(), plan))
        assertEquals(plan, roundTrip)
    }

    @Test fun uncheckedSentences() {
        assertEquals(
            "Not checked: plans don’t include grades",
            DegreeAudit.uncheckedReason("Minimum cumulative 2.000 GPA required in all CS, CY, DS, and IS courses"),
        )
        assertEquals(
            "Not checked: a plan doesn’t say where credits were earned",
            DegreeAudit.uncheckedReason("Students must earn a minimum of 64 Northeastern University semester hours."),
        )
        assertEquals(
            "Checked through the concentration you choose",
            DegreeAudit.uncheckedReason("Pick one of the following concentrations and complete four courses in that concentration."),
        )
        assertNull(DegreeAudit.uncheckedReason("Courses may not be taken pass/fail."))
    }

    private fun status(audit: DegreeAudit.Result, label: String) = audit.ruleResults.first { it.rule.label == label }.status
    private fun requiredChoose(rule: RequirementRule) = rule.kind as RequirementRule.Kind.Choose

    companion object {
        val program = CatalogProgram("Computer Science, BSCS (Boston)", "undergraduate/computer-information-science/computer-science/bscs/")
        val programPage = """
            <html><body>
            <div id="textcontainer" class="page_content"><p>About the program.</p></div>
            <div id="programrequirementstextcontainer" class="tab_content">
            <p>Complete all courses listed below unless otherwise indicated.</p>
            <h2>Major Requirements</h2>
            <table class="sc_courselist"><tbody>
            <tr class="hidden noscript"><th scope="col">Code</th><th scope="col">Title</th><th scope="col" class="hourscol">Hours</th></tr>
            <tr class="even areaheader firstrow"><td colspan="2"><span class="courselistcomment areaheader">Required Courses</span></td><td class="hourscol"></td></tr>
            <tr class="odd"><td class="codecol"><a href="/search/?P=CS%201800" title="CS 1800" class="bubblelink code">CS&#160;1800</a><span style="margin-left:20px;" class="blockindent">and <a href="/search/?P=CS%201802" class="bubblelink code">CS&#160;1802</a></span></td><td>Discrete Structures</td><td class="hourscol">5</td></tr>
            <tr class="even"><td class="codecol"><a class="bubblelink code">CS&#160;3000</a></td><td>Algorithms and Data</td><td class="hourscol">4</td></tr>
            <tr class="odd"><td class="codecol"><a class="bubblelink code">CS&#160;4500</a></td><td>Software Development</td><td class="hourscol">4</td></tr>
            <tr class="orclass odd"><td class="codecol orclass">or <a class="bubblelink code">CS&#160;4530</a></td><td colspan="2"> Fundamentals of Software Engineering</td></tr>
            <tr class="even areaheader"><td colspan="2"><span class="courselistcomment areaheader">Security Course</span></td><td class="hourscol"></td></tr>
            <tr class="odd"><td colspan="2"><span class="courselistcomment">Complete one of the following:</span></td><td class="hourscol">4</td></tr>
            <tr class="even"><td class="codecol"><a class="bubblelink code">CY&#160;2550</a></td><td>Foundations of Cybersecurity</td><td class="hourscol"></td></tr>
            <tr class="odd"><td class="codecol"><a class="bubblelink code">CY&#160;3740</a></td><td>Systems Security</td><td class="hourscol"></td></tr>
            <tr class="even areaheader"><td colspan="2"><span class="courselistcomment areaheader">Khoury Elective Courses</span></td><td class="hourscol"></td></tr>
            <tr class="odd"><td colspan="2"><span class="courselistcomment">With adviser approval, directed study may also be taken as an elective.</span></td><td class="hourscol"></td></tr>
            <tr class="even"><td colspan="2"><span class="courselistcomment">Complete 8 semester hours of CS, CY, DS, or IS classes that are not already required. Choose courses within the following ranges:</span></td><td class="hourscol">8</td></tr>
            <tr class="odd"><td colspan="2"><div style="margin-left:20px;" class="blockindent"><span class="courselistcomment commentindent"><a class="bubblelink code">CS&#160;2500</a> or higher, except <a class="bubblelink code">CS&#160;5010</a></span></div></td><td class="hourscol"></td></tr>
            <tr class="even lastrow"><td colspan="2"><div style="margin-left:20px;" class="blockindent"><span class="courselistcomment commentindent"><a class="bubblelink code">DS&#160;2500</a> to DS 4999 but not <a class="bubblelink code">DS&#160;4900</a></span></div></td><td class="hourscol"></td></tr>
            </tbody></table>
            <h2>Supporting Courses</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even areaheader firstrow"><td colspan="2"><span class="courselistcomment areaheader">Science Requirement</span></td><td class="hourscol"></td></tr>
            <tr class="odd"><td colspan="2"><span class="courselistcomment">Complete two courses (and any required labs) from the following science categories:</span></td><td class="hourscol">8</td></tr>
            <tr class="even areasubheader undefined subheader "><td colspan="2"><span class="courselistcomment areasubheader undefined"><i>Biology</i></span></td><td class="hourscol"></td></tr>
            <tr class="odd"><td class="codecol"><a class="bubblelink code">BIOL&#160;1111</a><br/><span class="blockindent">and <a class="bubblelink code">BIOL&#160;1112</a></span></td><td>General Biology 1<br/><span class="blockindent">and Lab for BIOL 1111</span></td><td class="hourscol"></td></tr>
            <tr class="even areasubheader undefined subheader "><td colspan="2"><span class="courselistcomment areasubheader undefined"><i>Mathematics</i></span></td><td class="hourscol"></td></tr>
            <tr class="odd lastrow"><td class="codecol"><a class="bubblelink code">MATH&#160;2331</a></td><td>Linear Algebra</td><td class="hourscol"></td></tr>
            </tbody></table>
            <h2>Required General Electives</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even lastrow firstrow"><td colspan="2"><span class="courselistcomment">Complete 8 semester hours of general electives.</span></td><td class="hourscol">8</td></tr>
            </tbody></table>
            <h2>Major GPA Requirement</h2>
            <p>Minimum 2.000 GPA required in the major.</p>
            <h2>Program Requirement</h2>
            <p>52 total semester hours required</p>
            <h2>Concentration in Human-Centered Computing*</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even firstrow"><td class="codecol"><a class="bubblelink code">CS&#160;2484</a></td><td>Principles of Human-Computer Interaction</td><td class="hourscol">4</td></tr>
            <tr class="odd"><td colspan="2"><span class="courselistcomment">Complete two of the following not already taken:</span></td><td class="hourscol">8</td></tr>
            <tr class="even"><td class="codecol"><a class="bubblelink code">CS&#160;4520</a></td><td>Mobile Application Development</td><td class="hourscol"></td></tr>
            <tr class="odd"><td class="codecol"><a class="bubblelink code">CS&#160;4550</a></td><td>Web Development</td><td class="hourscol"></td></tr>
            <tr class="even lastrow"><td class="codecol"><a class="bubblelink code">DS&#160;4200</a></td><td>Information Presentation and Visualization</td><td class="hourscol"></td></tr>
            </tbody></table>
            <p>*This concentration requires a fall co-op pattern.</p>
            <h2>Software Concentration</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even firstrow lastrow"><td class="codecol"><a class="bubblelink code">CS&#160;4400</a></td><td>Programming Languages</td><td class="hourscol">4</td></tr>
            </tbody></table>
            </div>
            <div id="planofstudytextcontainer" class="tab_content">
            <table class="sc_courselist"><tbody><tr><td class="codecol"><a>CS&#160;9999</a></td><td>Not a requirement</td><td class="hourscol">4</td></tr></tbody></table>
            </div>
            </body></html>
        """.trimIndent()

        fun requirements() = ProgramCatalogParser.requirements(programPage, CatalogYear(2023), program)!!

        fun course(code: String, credits: Double = 4.0, title: String = ""): DegreePlanCourse {
            val parts = code.split(" ")
            return DegreePlanCourse.of(CourseKey(parts[0], parts[1]), title, credits)
        }

        fun plan(): DegreePlan = DegreePlan(
            name = "Plan",
            completed = listOf(
                course("CS 1800"), course("CS 1802", 1.0), course("CS 3000"), course("CY 2550"),
                course("CS 4973", 4.0, "Topics in Computer Science"), course("CS 2484"), course("CS 4520"),
                course("CS 4535", 4.0, "Prof Practicum Capstone"), course("CS 2501", 1.0), course("MATH 1341"),
                course("CS 3000", 0.0),
            ),
            terms = listOf(
                DegreePlanTerm(
                    season = PlanSeason.SPRING,
                    year = 2027,
                    courses = listOf(course("CS 3800"), course("CS 4100"), course("DS 3000"), course("CS 4410")),
                ),
            ),
            catalogYear = 2023,
            catalogProgramPath = program.path,
            concentrationID = "concentration-in-human-centered-computing",
        )
    }
}

class DegreeNUpathTests {
    @Test fun everyNUpathRequirementIsChecked() {
        val audit = DegreeAudit.run(plan(), requirements(), attributes = attributes())
        val check = nupath(audit)!!
        assertEquals(NUpathRequirement.all.map { it.path.attributeCode }, check.items.map { it.id })
        assertTrue(check.items.all { it.status?.isSatisfied == true })
        assertEquals(DegreeAudit.Status.PLANNED, check.status)
        assertTrue(check.counts)
        assertEquals("13 of 13 met", check.detail)
        assertEquals("ENGW 1111 completed", item(check, NUPath.WF)?.detail)
        assertEquals(listOf("CS 4535", "CS 3484"), item(check, NUPath.WI)?.courses)
        assertEquals("CS 4535 planned · Spring 2027 · CS 3484 planned · Spring 2027", item(check, NUPath.WI)?.detail)
        assertEquals(listOf("CS 4535"), item(check, NUPath.CE)?.courses)
        assertEquals(listOf("COOP 3946"), item(check, NUPath.EX)?.courses)
        assertEquals(DegreeAudit.Status.COMPLETE, item(check, NUPath.EX)?.status)
        val areas = HashMap<String, Int>()
        for (requirement in NUpathRequirement.all.filter { it.isLimited }) {
            for (id in item(check, requirement.path)?.courses.orEmpty()) areas[id] = (areas[id] ?: 0) + 1
        }
        assertTrue(areas.values.all { it <= 2 })
        assertTrue(audit.countedChecks.any { it.kind is DegreeAudit.NoteCheck.Kind.Nupath && it.status.isSatisfied })
    }

    @Test fun aCourseCountsForTwoAreasAtMost() {
        val table = attributesTable().toMutableMap()
        table["ARTH 1111"] = setOf(NUPath.IC, NUPath.EI, NUPath.DD)
        table["HIST 1130"] = emptySet()
        val baseline = DegreeAudit.run(plan(), requirements(), attributes = attributes())
        val audit = DegreeAudit.run(plan(), requirements(), attributes = attributes(table))
        val check = nupath(audit)!!
        val open = check.items.filter { it.status?.isSatisfied != true }
        assertEquals(1, open.size)
        assertEquals(DegreeAudit.Status.MISSING, open.first().status)
        assertEquals("No course in the plan has it", open.first().detail)
        assertEquals(DegreeAudit.Status.PARTIAL, check.status)
        assertTrue(check.counts)
        assertTrue(check.detail.startsWith("12 of 13 met · missing "))
        assertEquals(baseline.satisfiedCount - 1, audit.satisfiedCount)
        assertEquals(baseline.totalCount, audit.totalCount)
    }

    @Test fun unknownCoursesKeepAShortfallFromCounting() {
        val requirements = requirements()
        val table = attributesTable().toMutableMap()
        table.remove("HIST 1130")
        val audit = DegreeAudit.run(plan(), requirements, attributes = attributes(table))
        val check = nupath(audit)!!
        assertNull(item(check, NUPath.DD)?.status)
        assertEquals("Not found among the courses with NUpath data", item(check, NUPath.DD)?.detail)
        assertFalse(check.counts)
        assertEquals("12 of 13 met · no NUpath data for HIST 1130", check.detail)
        assertFalse(audit.countedChecks.any { it.kind is DegreeAudit.NoteCheck.Kind.Nupath })
        val loading = DegreeAudit.run(plan(), requirements, attributes = attributes(table, loading = listOf("HIST 1130")))
        val pending = nupath(loading)!!
        assertEquals("Looking up the plan’s courses…", item(pending, NUPath.DD)?.detail)
        assertEquals("12 of 13 met · looking up 1 course", pending.detail)
        table["ARTH 1111"] = setOf(NUPath.IC, NUPath.DD)
        table["CS 3000"] = setOf(NUPath.EI)
        val met = nupath(DegreeAudit.run(plan(), requirements, attributes = attributes(table)))!!
        assertTrue(met.counts)
        assertEquals(DegreeAudit.Status.PLANNED, met.status)
    }

    @Test fun withoutAttributesNUpathIsNotChecked() {
        assertNull(nupath(DegreeAudit.run(plan(), requirements())))
    }

    @Test fun aRenumberedCourseUsesItsReplacementsAttributes() {
        val original = plan()
        val completed = original.completed.toMutableList()
        completed[2] = DegreeAuditTests.course("PHYS 1145")
        val table = attributesTable().toMutableMap()
        table.remove("PHYS 1151")
        table["PHYS 1149"] = setOf(NUPath.ND)
        val plan = original.copy(
            completed = completed,
            courseEquivalences = listOf(
                app.termorganize.logic.degree.CourseEquivalence(course = key("PHYS 1145"), countsAs = key("PHYS 1149"), note = "Renumbered"),
            ),
        )
        val check = nupath(DegreeAudit.run(plan, requirements(), attributes = attributes(table)))!!
        assertEquals(listOf("PHYS 1145"), item(check, NUPath.ND)?.courses)
        assertTrue(check.counts)
    }

    @Test fun withoutACoopAnExperienceCourseCounts() {
        val plan = plan().copy(completed = plan().completed.filter { it.key?.subject != "COOP" })
        val audit = DegreeAudit.run(plan, requirements(), attributes = attributes())
        val coop = audit.noteChecks.first { it.kind is DegreeAudit.NoteCheck.Kind.Coop }
        assertEquals(DegreeAudit.Status.PLANNED, coop.status)
        assertEquals("No co-op in the plan · CS 4535 (EX) planned", coop.detail)
        assertEquals(listOf("CS 4535"), item(nupath(audit), NUPath.EX)?.courses)
    }

    @Test fun matchingIsOptimal() {
        val random = SplitMix(2016)
        val areas = NUpathRequirement.all.filter { it.isLimited }.map { it.path }
        repeat(600) {
            val wanted = random.shuffled(areas).take(random.int(1, 5))
            val courses = (0 until random.int(1, 5)).map { index ->
                NUpathMatching.Course(
                    "C$index",
                    wanted.filter { random.int(0, 9) < 4 }.toSet(),
                    random.boolean(),
                )
            }
            val matching = NUpathMatching(courses, wanted.map { NUpathRequirement(it, 1, true) })
            val chosen = wanted.mapNotNull { area -> matching.courses(area).firstOrNull()?.let { area to it } }
            val completed = chosen.count { pair -> courses.first { it.id == pair.second }.isCompleted }
            assertTrue(chosen.all { pair -> courses.first { it.id == pair.second }.paths.contains(pair.first) })
            assertTrue(chosen.groupBy { it.second }.values.all { it.size <= 2 })
            var bestMet = 0
            var bestCompleted = 0
            fun search(position: Int, load: IntArray, met: Int, done: Int) {
                if (position >= wanted.size) {
                    if (met > bestMet || (met == bestMet && done > bestCompleted)) {
                        bestMet = met
                        bestCompleted = done
                    }
                    return
                }
                search(position + 1, load, met, done)
                for ((index, course) in courses.withIndex()) {
                    if (wanted[position] !in course.paths || load[index] >= 2) continue
                    load[index]++
                    search(position + 1, load, met + 1, done + if (course.isCompleted) 1 else 0)
                    load[index]--
                }
            }
            search(0, IntArray(courses.size), 0, 0)
            assertEquals(bestMet, chosen.size)
            assertEquals(bestCompleted, completed)
        }
    }

    @Test fun readsTheUniversitywideMinimums() {
        val archived = """
            <div id="textcontainer"><p>Note: Individual program requirements may exceed the following minima.</p>
            <ul><li>Minimum 128 total semester hours required</li><li>Minimum 2.000 GPA required</li></ul>
            <p>Students must earn a minimum of 64 Northeastern University semester hours in order to receive a bachelor’s degree.</p></div>
        """.trimIndent()
        val minimums = ProgramCatalogParser.universitywideRequirements(archived)!!
        assertEquals(128.0, minimums.totalHours!!, 0.0)
        assertEquals(2.0, minimums.gpa!!, 0.0)
        assertEquals(64.0, minimums.residencyHours!!, 0.0)
        assertFalse(minimums.requiresExperientialLearning)
        val current = archived.replace("</div>", "<p>Students must complete all university and program requirements, including the experiential learning graduation requirement.</p></div>")
        assertTrue(ProgramCatalogParser.universitywideRequirements(current)!!.requiresExperientialLearning)
        assertNull(ProgramCatalogParser.universitywideRequirements("""<div id="textcontainer"><p>Nothing here.</p></div>"""))
        assertEquals(
            "undergraduate/university-academics/university-wide-requirements/",
            ProgramCatalogParser.universitywideLink(page),
        )
    }

    @Test fun checksTheUniversitywideMinimumsItCan() {
        val minimums = UniversitywideRequirements(totalHours = 128.0, gpa = 2.0, residencyHours = 64.0)
        val audit = DegreeAudit.run(plan(), requirements(minimums), attributes = attributes())
        val check = audit.noteChecks.first { it.kind is DegreeAudit.NoteCheck.Kind.Universitywide }
        assertEquals(listOf("total-hours", "gpa", "residency"), check.items.map { it.id })
        assertEquals(DegreeAudit.Status.PARTIAL, check.items[0].status)
        assertEquals("32 of 128 semester hours · 12 planned", check.items[0].detail)
        assertNull(check.items[1].status)
        assertEquals("Not checked: plans don’t include grades", check.items[1].detail)
        assertNull(check.items[2].status)
        assertEquals(DegreeAudit.Status.PARTIAL, check.status)
        assertTrue(check.counts)
        assertEquals("32 of 128 semester hours · 12 planned · GPA and residency not checked", check.detail)
        val unread = DegreeAudit.run(plan(), requirements(), attributes = attributes())
        assertFalse(unread.noteChecks.any { it.kind is DegreeAudit.NoteCheck.Kind.Universitywide })
    }

    @Test fun checksTheProgramsTotalHours() {
        val audit = DegreeAudit.run(plan(), requirements())
        val check = audit.noteChecks.first { it.kind is DegreeAudit.NoteCheck.Kind.TotalHours }
        assertEquals(DegreeAudit.Status.PARTIAL, check.status)
        assertEquals("32 of 134 semester hours · 12 planned", check.detail)
    }

    @Test fun hoursLinesKeepCompletedSeparateFromPlanned() {
        assertEquals(DegreeAudit.Status.PLANNED to "105 of 128 semester hours · 30 planned", DegreeAudit.hoursStatus(105.0, 30.0, 128.0))
        assertEquals(DegreeAudit.Status.PLANNED to "105 of 134 semester hours · 30 planned", DegreeAudit.hoursStatus(105.0, 30.0, 134.0))
        assertEquals(DegreeAudit.Status.PLANNED to "60 of 72 semester hours · 22 planned", DegreeAudit.hoursStatus(60.0, 22.0, 72.0))
        assertEquals(DegreeAudit.Status.COMPLETE to "128 of 128 semester hours", DegreeAudit.hoursStatus(128.0, 0.0, 128.0))
        assertEquals(DegreeAudit.Status.COMPLETE to "140 of 128 semester hours · 10 planned", DegreeAudit.hoursStatus(140.0, 10.0, 128.0))
        assertEquals(DegreeAudit.Status.PARTIAL to "90 of 128 semester hours · 20 planned", DegreeAudit.hoursStatus(90.0, 20.0, 128.0))
        assertEquals(DegreeAudit.Status.MISSING to "0 of 128 semester hours", DegreeAudit.hoursStatus(0.0, 0.0, 128.0))
        assertEquals(DegreeAudit.Status.PLANNED to "0 of 128 semester hours · 128 planned", DegreeAudit.hoursStatus(0.0, 128.0, 128.0))

        val page = """
            <div id="programrequirementstextcontainer" class="tab_content">
            <h2>Universitywide Requirements</h2>
            <p>All undergraduate students are required to complete the Universitywide Requirements.</p>
            <h2>Major Requirements</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even firstrow"><td class="codecol"><a class="bubblelink code">CS&#160;3000</a></td><td>Algorithms</td><td class="hourscol">4</td></tr>
            <tr class="odd"><td colspan="2"><span class="courselistcomment">Complete 8 semester hours of CS classes. Choose courses within the following ranges:</span></td><td class="hourscol">8</td></tr>
            <tr class="even lastrow"><td colspan="2"><div style="margin-left:20px;" class="blockindent"><span class="courselistcomment commentindent"><a class="bubblelink code">CS&#160;2500</a> or higher</span></div></td><td class="hourscol"></td></tr>
            </tbody></table>
            <h2>Writing Requirement</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even firstrow lastrow"><td class="codecol"><a class="bubblelink code">ENGW&#160;1111</a></td><td>First-Year Writing</td><td class="hourscol">4</td></tr>
            </tbody></table>
            <h2>Required General Electives</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even lastrow firstrow"><td colspan="2"><span class="courselistcomment">Complete 4 semester hours of general electives.</span></td><td class="hourscol">4</td></tr>
            </tbody></table>
            <h2>Credit Requirement</h2>
            <p>Complete 72 semester hours in the major.</p>
            <h2>Program Requirement</h2>
            <p>134 total semester hours required</p>
            </div>
        """.trimIndent()
        val requirements = ProgramCatalogParser.requirements(page, CatalogYear(2024), program)!!.copy(
            universitywide = UniversitywideRequirements(totalHours = 128.0, gpa = 2.0, residencyHours = 64.0),
        )
        val plan = DegreePlan(
            name = "BS CS",
            completed = listOf(
                DegreeAuditTests.course("CS 3000", 40.0),
                DegreeAuditTests.course("CS 2500", 20.0),
                DegreeAuditTests.course("ENGW 1111", 4.0),
                DegreeAuditTests.course("PHIL 1102", 41.0),
            ),
            terms = listOf(
                DegreePlanTerm(
                    season = PlanSeason.SPRING,
                    year = 2027,
                    courses = listOf(DegreeAuditTests.course("CS 3500", 22.0), DegreeAuditTests.course("MKTG 2201", 8.0)),
                ),
            ),
        )
        val audit = DegreeAudit.run(plan, requirements)
        assertEquals(105.0, audit.completedHours, 0.0)
        assertEquals(30.0, audit.plannedHours, 0.0)
        assertEquals(134.0, audit.requiredHours!!, 0.0)
        assertEquals("105 of 134 credits done · 30 planned", DegreeRequirementText.creditsLine(audit))

        val university = audit.noteChecks.first { it.kind is DegreeAudit.NoteCheck.Kind.Universitywide }
        assertEquals(DegreeAudit.Status.PLANNED, university.status)
        assertEquals(DegreeAudit.Status.PLANNED, university.items[0].status)
        assertEquals("105 of 128 semester hours · 30 planned", university.items[0].detail)
        assertEquals("105 of 128 semester hours · 30 planned · GPA and residency not checked", university.detail)

        val total = audit.noteChecks.first { it.kind is DegreeAudit.NoteCheck.Kind.TotalHours }
        assertEquals(DegreeAudit.Status.PLANNED, total.status)
        assertEquals("105 of 134 semester hours · 30 planned", total.detail)

        val major = audit.noteChecks.first { it.kind is DegreeAudit.NoteCheck.Kind.MajorHours }
        assertEquals(DegreeAudit.Status.PLANNED, major.status)
        assertEquals("60 of 72 semester hours · 22 planned", major.detail)
    }

    private fun nupath(audit: DegreeAudit.Result) = audit.noteChecks.firstOrNull { it.kind is DegreeAudit.NoteCheck.Kind.Nupath }
    private fun item(check: DegreeAudit.NoteCheck?, path: NUPath) = check?.items?.firstOrNull { it.id == path.attributeCode }

    companion object {
        val program = CatalogProgram("Computer Science, BSCS (Boston)", "undergraduate/computer-information-science/computer-science/bscs/")
        val page = """
            <div id="programrequirementstextcontainer" class="tab_content">
            <h2>Universitywide Requirements</h2>
            <p>All undergraduate students are required to complete the <a href="/archive/2024-2025/undergraduate/university-academics/university-wide-requirements/">Universitywide Requirements</a>.</p>
            <h2>NUpath Requirements</h2>
            <p>All undergraduate students are required to complete the <a href="/archive/2024-2025/undergraduate/university-academics/nupath/">NUpath Requirements</a>.</p>
            <h2>Major Requirements</h2>
            <table class="sc_courselist"><tbody>
            <tr class="even firstrow lastrow"><td class="codecol"><a class="bubblelink code">CS&#160;3000</a></td><td>Algorithms and Data</td><td class="hourscol">4</td></tr>
            </tbody></table>
            <h2>Khoury College GPA Requirement</h2>
            <p>Minimum cumulative 2.000 GPA required in all CS, CY, DS, and IS courses</p>
            <h2>NUpath Requirements Satisfied</h2>
            <p>Integrating Knowledge and Skills Through Experience is satisfied through co-op.</p>
            <h2>Program Requirement</h2>
            <p>134 total semester hours required</p>
            </div>
        """.trimIndent()

        fun requirements(universitywide: UniversitywideRequirements? = null) =
            ProgramCatalogParser.requirements(page, CatalogYear(2024), program)!!.copy(universitywide = universitywide)

        fun plan() = DegreePlan(
            name = "NUpath",
            completed = listOf("ENGW 1111", "MATH 1341", "PHYS 1151", "CS 3200", "PHIL 1145", "ARTH 1111", "HIST 1130", "CS 3000").map { DegreeAuditTests.course(it) } +
                DegreeAuditTests.course("COOP 3946", 0.0, "Co-op Work Experience - HT"),
            terms = listOf(
                DegreePlanTerm(
                    season = PlanSeason.SPRING,
                    year = 2027,
                    courses = listOf(DegreeAuditTests.course("ENGW 3302"), DegreeAuditTests.course("CS 4535"), DegreeAuditTests.course("CS 3484")),
                ),
            ),
        )

        fun key(code: String): CourseKey {
            val parts = code.split(" ")
            return CourseKey(parts[0], parts[1])
        }

        fun attributesTable() = linkedMapOf(
            "ENGW 1111" to setOf(NUPath.WF),
            "MATH 1341" to setOf(NUPath.FQ),
            "PHYS 1151" to setOf(NUPath.ND),
            "CS 3200" to setOf(NUPath.AD, NUPath.FQ),
            "PHIL 1145" to setOf(NUPath.ER, NUPath.SI),
            "ARTH 1111" to setOf(NUPath.IC, NUPath.EI),
            "HIST 1130" to setOf(NUPath.DD, NUPath.SI),
            "CS 3000" to setOf(NUPath.FQ),
            "ENGW 3302" to setOf(NUPath.WD),
            "CS 4535" to setOf(NUPath.CE, NUPath.EX, NUPath.WI),
            "CS 3484" to setOf(NUPath.WI),
        )

        fun attributes(table: Map<String, Set<NUPath>> = attributesTable(), loading: List<String> = emptyList()) =
            DegreeCourseAttributes(table.mapKeys { key(it.key) }, loading.map { key(it) }.toSet())
    }
}

class DegreeAuditMatchingTests {
    @Test fun matchesAsManyRequirementsAsPossible() {
        val random = SplitMix(2026)
        repeat(160) {
            val plan = randomPlan(random)
            val rules = randomRules(plan.courses, random)
            val pins = randomPins(rules, plan.courses, random)
            val degreePlan = plan.degreePlan.copy(
                substitutions = pins.map { pin ->
                    RequirementSubstitution(
                        ruleID = rules[pin.second].id,
                        ruleTitle = rules[pin.second].id,
                        change = SubstitutionChange.CountCourse(plan.courses[pin.first].key),
                    )
                },
            )
            val requirements = requirements(rules)
            val audit = DegreeAudit.run(degreePlan, requirements)
            val possible = mostThatCanBeMet(rules, plan.courses, pins)
            val specificMet = audit.ruleResults.count { !it.rule.degreeIsGeneralElectives && it.status.isSatisfied }
            assertEquals(describe(rules, plan.courses), possible.specific, specificMet)
            if (possible.everything) assertEquals(describe(rules, plan.courses), audit.totalCount, audit.satisfiedCount)
            val counted = audit.ruleResults.flatMap { it.used.map { use -> use.id } }
            assertEquals(counted.size, counted.toSet().size)
        }
    }

    @Test fun largePlansStayFast() {
        val cs = pool.filter { it.key.subject == "CS" && it.credits >= 4 }.map { it.key }
        val rules = (0 until 6).map { index ->
            val options = SplitMix(index.toLong()).shuffled(cs).take(5).map { CourseOption(listOf(listOf(it))) }
            rule("list-$index", RequirementRule.Kind.Choose(2, null, options, emptyList()))
        } + listOf(
            rule("electives", RequirementRule.Kind.Choose(null, 12.0, emptyList(), listOf(CourseRange("CS", 2000, null, emptyList(), "CS 2000 or higher")))),
            rule("petition", RequirementRule.Kind.Choose(3, null, emptyList(), listOf(CourseRange("CS", 0, null, emptyList(), "Any CS course")))),
            rule("general", RequirementRule.Kind.GeneralElectives(16.0)),
        )
        val plan = DegreePlan(name = "Big", completed = pool.map { DegreePlanCourse.of(it.key, "", it.credits) })
        val start = System.nanoTime()
        val audit = DegreeAudit.run(plan, requirements(rules))
        assertTrue(System.nanoTime() - start < 2_000_000_000L)
        assertEquals(rules.size, audit.totalCount)
    }

    private data class PoolCourse(val key: CourseKey, val credits: Double)
    private data class RandomPlan(val courses: List<PoolCourse>, val degreePlan: DegreePlan)

    private fun randomPlan(random: SplitMix): RandomPlan {
        val courses = random.shuffled(pool).take(random.int(3, 6))
        val completed = mutableListOf<DegreePlanCourse>()
        val planned = mutableListOf<DegreePlanCourse>()
        for (course in courses) {
            val line = DegreePlanCourse.of(course.key, "", course.credits)
            if (random.boolean()) completed += line else planned += line
        }
        return RandomPlan(courses, DegreePlan(name = "Random", completed = completed, terms = listOf(DegreePlanTerm(season = PlanSeason.SPRING, year = 2027, courses = planned))))
    }

    private fun randomRules(courses: List<PoolCourse>, random: SplitMix): List<RequirementRule> {
        val keys = courses.map { it.key }
        fun someKey() = if (random.int(0, 4) == 0) random.pick(pool).key else random.pick(keys)
        val total = random.int(2, 4)
        val withElectives = random.boolean()
        val rules = mutableListOf<RequirementRule>()
        val count = if (withElectives) total - 1 else total
        for (index in 0 until count) {
            val kind: RequirementRule.Kind = when (random.int(0, 7)) {
                0 -> RequirementRule.Kind.Required(CourseOption(listOf(listOf(someKey()))))
                1 -> RequirementRule.Kind.Required(CourseOption(listOf(distinct(listOf(someKey(), someKey())))))
                2 -> RequirementRule.Kind.Required(CourseOption(listOf(listOf(someKey()), listOf(someKey()))))
                3 -> {
                    val options = distinct((0 until random.int(2, 4)).map { someKey() })
                    RequirementRule.Kind.Choose(random.int(1, 2), null, options.map { CourseOption(listOf(listOf(it))) }, emptyList())
                }
                4 -> {
                    val lowest = random.pick(listOf(2000, 3000))
                    RequirementRule.Kind.Choose(random.int(1, 2), null, emptyList(), listOf(CourseRange("CS", lowest, null, emptyList(), "CS $lowest or higher")))
                }
                5 -> RequirementRule.Kind.Choose(
                    null,
                    random.pick(listOf(5.0, 8.0)),
                    emptyList(),
                    listOf(
                        CourseRange("CS", 2000, null, emptyList(), "CS 2000 or higher"),
                        CourseRange("DS", 2000, null, emptyList(), "DS 2000 or higher"),
                    ),
                )
                6 -> {
                    val pair = distinct(listOf(someKey(), someKey()))
                    val singles = distinct((0 until random.int(1, 2)).map { someKey() }).filter { it !in pair }
                    RequirementRule.Kind.Choose(
                        random.int(1, 2),
                        null,
                        listOf(CourseOption(listOf(pair))) + singles.map { CourseOption(listOf(listOf(it))) },
                        emptyList(),
                    )
                }
                else -> {
                    val ranges = listOf(CourseRange("CS", 2500, null, emptyList(), "CS 2500 or higher"))
                    val options = listOf(CourseOption(listOf(listOf(someKey()))))
                    if (random.boolean()) RequirementRule.Kind.Choose(random.int(1, 2), null, options, ranges)
                    else RequirementRule.Kind.Choose(null, random.pick(listOf(4.0, 8.0)), options, ranges)
                }
            }
            rules += rule("rule-$index", kind)
        }
        if (withElectives) rules += rule("general", RequirementRule.Kind.GeneralElectives(random.pick(listOf(4.0, 8.0))))
        return rules
    }

    private fun distinct(keys: List<CourseKey>): List<CourseKey> {
        val seen = LinkedHashSet<CourseKey>()
        return keys.filter { seen.add(it) }
    }

    private fun randomPins(rules: List<RequirementRule>, courses: List<PoolCourse>, random: SplitMix): List<Pair<Int, Int>> {
        if (random.int(0, 9) >= 3) return emptyList()
        return listOf(random.int(0, courses.lastIndex) to random.int(0, rules.lastIndex))
    }

    private fun mostThatCanBeMet(rules: List<RequirementRule>, courses: List<PoolCourse>, pins: List<Pair<Int, Int>>): Possible {
        val assignment = IntArray(courses.size) { rules.size }
        val pinned = pins.associate { it.first to it.second }
        var bestSpecific = 0
        var everything = false
        fun isMet(ruleIndex: Int): Boolean {
            val rule = rules[ruleIndex]
            val mine = courses.indices.filter { assignment[it] == ruleIndex && pinned[it] == null }.map { courses[it] }
            val placed = courses.indices.filter { pinned[it] == ruleIndex }.map { courses[it] }
            val keys = mine.map { it.key }.toSet()
            val placedKeys = placed.map { it.key }.toSet()
            fun complete(alternative: List<CourseKey>) = alternative.all { it in keys }
            return when (val kind = rule.kind) {
                is RequirementRule.Kind.Required -> placed.isNotEmpty() || kind.option.alternatives.any { complete(it) }
                is RequirementRule.Kind.Choose -> {
                    val open = kind.options.filter { option -> option.allCourses.all { it !in placedKeys } }
                    val listed = open.flatMap { it.allCourses }.toSet()
                    val optionUnits = open.count { option -> option.alternatives.any { complete(it) } }
                    val inRanges = mine.filter { course -> course.key !in listed && kind.ranges.any { it.contains(course.key) } }
                    when {
                        kind.count != null -> placed.size + optionUnits + inRanges.count { it.credits >= 2 } >= kind.count
                        kind.hours != null -> {
                            val fromOptions = mine.filter { course ->
                                open.any { option -> course.key in option.allCourses && option.alternatives.any { complete(it) } }
                            }
                            (placed + fromOptions + inRanges).sumOf { it.credits } >= kind.hours
                        }
                        else -> placed.size + optionUnits >= 1
                    }
                }
                is RequirementRule.Kind.GeneralElectives -> (mine + placed).sumOf { it.credits } >= kind.hours
            }
        }
        fun assign(position: Int) {
            if (position >= courses.size) {
                val met = rules.indices.filter { isMet(it) }
                val specific = met.count { !rules[it].degreeIsGeneralElectives }
                bestSpecific = maxOf(bestSpecific, specific)
                if (met.size == rules.size) everything = true
                return
            }
            val forced = pinned[position]
            if (forced != null) {
                assignment[position] = forced
                assign(position + 1)
                return
            }
            for (bucket in 0..rules.size) {
                assignment[position] = bucket
                assign(position + 1)
            }
        }
        assign(0)
        return Possible(bestSpecific, everything)
    }

    private data class Possible(val specific: Int, val everything: Boolean)

    private fun describe(rules: List<RequirementRule>, courses: List<PoolCourse>): String {
        val plan = courses.joinToString(", ") { "${it.key.description} (${it.credits})" }
        return "plan: $plan; rules: ${rules.size}"
    }

    private class SplitMix(seed: Long) {
        private var state = seed
        fun nextLong(): Long {
            state += 0x9E3779B97F4A7C15u.toLong()
            var z = state
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9u.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBu.toLong()
            return z xor (z ushr 31)
        }
        fun int(from: Int, to: Int) = from + (nextLong().ushr(1) % (to - from + 1)).toInt()
        fun boolean() = int(0, 1) == 1
        fun <T> pick(list: List<T>) = list[int(0, list.lastIndex)]
        fun <T> shuffled(list: List<T>): List<T> {
            val copy = list.toMutableList()
            for (i in copy.lastIndex downTo 1) {
                val j = int(0, i)
                val tmp = copy[i]
                copy[i] = copy[j]
                copy[j] = tmp
            }
            return copy
        }
    }

    companion object {
        private val catalog = listOf(
            "CS 1200" to 1.0, "CS 1800" to 4.0, "CS 1802" to 1.0, "CS 2500" to 4.0, "CS 2501" to 1.0, "CS 2510" to 4.0,
            "CS 3000" to 4.0, "CS 3200" to 4.0, "CS 3500" to 4.0, "CS 3501" to 1.0, "CS 3650" to 4.0, "CS 4100" to 4.0,
            "CS 4400" to 4.0, "CS 4530" to 4.0, "DS 3000" to 4.0, "DS 4400" to 4.0, "MATH 1341" to 4.0, "MATH 2331" to 4.0,
        )
        private val pool = catalog.map { (code, credits) ->
            val parts = code.split(" ")
            PoolCourse(CourseKey(parts[0], parts[1]), credits)
        }

        fun rule(id: String, kind: RequirementRule.Kind) = RequirementRule(id, kind, id)
        fun requirements(rules: List<RequirementRule>): ProgramRequirements {
            val groups = rules.map { RequirementGroup("group-${it.id}", it.id, listOf(it)) }
            return ProgramRequirements(
                CatalogYear(2024),
                CatalogProgram("Random, BS", "undergraduate/random/"),
                listOf(RequirementSection("major", "Major", false, groups)),
            )
        }
    }
}

private class SplitMix(seed: Long) {
    private var state = seed
    fun nextLong(): Long {
        state += 0x9E3779B97F4A7C15u.toLong()
        var z = state
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9u.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBu.toLong()
        return z xor (z ushr 31)
    }
    fun int(from: Int, to: Int) = from + (nextLong().ushr(1) % (to - from + 1)).toInt()
    fun boolean() = int(0, 1) == 1
    fun <T> shuffled(list: List<T>): List<T> {
        val copy = list.toMutableList()
        for (i in copy.lastIndex downTo 1) {
            val j = int(0, i)
            val tmp = copy[i]
            copy[i] = copy[j]
            copy[j] = tmp
        }
        return copy
    }
}
