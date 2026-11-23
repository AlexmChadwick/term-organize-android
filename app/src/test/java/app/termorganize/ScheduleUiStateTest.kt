// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.core.model.CourseKey
import app.termorganize.logic.degree.PlanCourse
import app.termorganize.logic.degree.SchedulePlan
import app.termorganize.logic.schedule.ScheduleGenerationResult
import app.termorganize.logic.schedule.SchedulePreferences
import app.termorganize.ui.ScheduleStamp
import app.termorganize.ui.ScheduleUiState
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScheduleUiStateTest {
    private val plan = SchedulePlan(name = "A", termCode = "202710", courses = listOfNotNull(CourseKey.parse("CS 3000")?.let { PlanCourse(it, colorIndex = 0) }))
    private val result = ScheduleGenerationResult(totalFound = 3)

    @Test fun keptResultSurvivesUntilTheTermChanges() {
        val ui = ScheduleUiState()
        ui.store(ScheduleStamp.of(plan, "202710"), result)
        assertSame(result, ui.resultFor(ScheduleStamp.of(plan, "202710")))
        assertNull(ui.resultFor(ScheduleStamp.of(plan, "202730")))
    }

    @Test fun keptResultIsDroppedWhenTheCoursesOrPlanChange() {
        val ui = ScheduleUiState()
        ui.store(ScheduleStamp.of(plan, "202710"), result)
        assertNull(ui.resultFor(ScheduleStamp.of(plan.copy(courses = emptyList()), "202710")))
        assertNull(ui.resultFor(ScheduleStamp.of(plan.copy(id = "other"), "202710")))
    }

    @Test fun keptResultIsDroppedWhenPreferencesChange() {
        val ui = ScheduleUiState()
        ui.store(ScheduleStamp.of(plan, "202710"), result)
        val changed = plan.copy(preferences = SchedulePreferences(maxClassesPerDay = 2))
        assertNull(ui.resultFor(ScheduleStamp.of(changed, "202710")))
    }
}
