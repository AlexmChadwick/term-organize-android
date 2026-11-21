package app.termorganize

import app.termorganize.core.model.AppJson
import app.termorganize.core.model.CourseSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitlistDisplayTests {
    private fun section(seats: Int, cap: Int = 0, count: Int = 0) = CourseSection(
        "39766", "INAM", "1925", seatCapacity = 15, seatsRemaining = seats,
        waitlistCapacity = cap, waitlistCount = count,
    )

    @Test fun noWaitlist() {
        val s = section(seats = 0)
        assertNull(s.waitlistLabel)
        assertEquals("Full", s.availabilityLabel)
        assertFalse(s.isOpen)
        assertTrue(section(seats = 3).isOpen)
    }

    @Test fun waitlistWithNoSeats() {
        val s = section(seats = 0, cap = 10, count = 7)
        assertEquals("Waitlist 7/10", s.waitlistLabel)
        assertEquals("Full", s.availabilityLabel)
        assertFalse(s.isOpen)
    }

    @Test fun waitlistQueueWithSeatsIsNotOpen() {
        val s = section(seats = 2, cap = 10, count = 3)
        assertEquals("Waitlist 3/10", s.waitlistLabel)
        assertEquals("Waitlist", s.availabilityLabel)
        assertFalse(s.isOpen)
        assertEquals(0, s.openSeats)
    }

    @Test fun emptyWaitlistWithSeatsIsOpen() {
        val s = section(seats = 2, cap = 10, count = 0)
        assertEquals("Waitlist 0/10", s.waitlistLabel)
        assertEquals("Open", s.availabilityLabel)
        assertTrue(s.isOpen)
        assertEquals(2, s.openSeats)
    }

    @Test fun cachedSectionWithoutWaitFieldsDecodesAsZero() {
        val s = AppJson.catalog.decodeFromString(
            CourseSection.serializer(),
            """{"crn":"1","subject":"CS","number":"1000","seatCapacity":10,"seatsRemaining":4}""",
        )
        assertEquals(0, s.waitlistCapacity)
        assertEquals(0, s.waitlistCount)
        assertTrue(s.isOpen)
    }
}
