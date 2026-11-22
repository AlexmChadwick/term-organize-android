// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize

import app.termorganize.ui.RouteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleBackNavigationTest {
    @Test fun courseOpenedFromScheduleReturnsToSchedule() {
        val nav = RouteState("home")
        nav.navigate("schedule")
        nav.navigate("course/CS/3000")
        assertEquals("course/CS/3000", nav.route)
        nav.closeDetail()
        assertEquals("schedule", nav.route)
        assertNull(nav.backRoute)
    }

    @Test fun sectionOpenedFromScheduleReturnsToScheduleEvenAfterDetailToDetail() {
        val nav = RouteState("home")
        nav.navigate("schedule")
        nav.navigate("course/CS/3000")
        nav.navigate("section/12345")
        nav.closeDetail()
        assertEquals("schedule", nav.route)
        assertNull(nav.backRoute)
    }

    @Test fun searchOriginBackStillReturnsToSearch() {
        val nav = RouteState("home")
        nav.navigate("search")
        nav.navigate("course/CS/3000")
        nav.closeDetail()
        assertEquals("search", nav.route)
        nav.navigate("home")
        nav.navigate("section/12345")
        nav.closeDetail()
        assertEquals("search", nav.route)
        assertNull(nav.backRoute)
    }
}
