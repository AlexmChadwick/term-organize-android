package app.termorganize

import app.termorganize.core.banner.BannerFaculty
import app.termorganize.core.banner.BannerMeetingFaculty
import app.termorganize.core.banner.BannerSection
import app.termorganize.core.banner.CatalogBuilder
import app.termorganize.core.model.AppJson
import app.termorganize.core.model.CourseSection
import app.termorganize.core.model.Instructor
import app.termorganize.core.model.Term
import app.termorganize.core.model.TermCatalog
import app.termorganize.data.CatalogStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runtime paths that must scrub instructor emails: store load and save, Banner conversion, cached fallback. */
class EmailScrubPathTests {
    private val dirty = listOf(
        Instructor("Husky, A", true, "a@husky.neu.edu"),
        Instructor("External, B", false, "b@example.com"),
        Instructor("Dot, C", false, "c@neu.edu."),
        Instructor("Mixed, D", false, "d@example.com, d2@NEU.edu"),
        Instructor("Clean, E", false, "e@northeastern.edu"),
    )
    private val cleaned = listOf(null, null, null, "d2@NEU.edu", "e@northeastern.edu")

    private fun catalog() = TermCatalog(
        term = Term("202710", "Fall 2026 Semester"),
        sections = listOf(CourseSection("1", "CS", "3000", instructors = dirty)),
    )

    private fun emails(c: TermCatalog) = c.sections.single().instructors!!.map { it.email }

    private fun tempDir() = File(System.getProperty("java.io.tmpdir"), "nto-scrub-${System.nanoTime()}").apply { mkdirs() }

    @Test fun saveWritesOnlyInstitutionalEmails() {
        val directory = tempDir()
        CatalogStore(directory).save(catalog())
        val raw = File(directory, directory.listFiles()!!.single { it.name.startsWith("term-202710") }.name).readText()
        assertEquals(emptyList<String>(), LogicTests.rawEmailViolations(raw))
        assertTrue(raw.contains("d2@NEU.edu"))
        assertTrue(!raw.contains("husky"))
    }

    @Test fun loadScrubsDownloadedCatalog() {
        val directory = tempDir()
        val name = CatalogStore(directory).also { it.save(TermCatalog(term = Term("202710", "Fall 2026 Semester"))) }
            .let { directory.listFiles()!!.single { f -> f.name.startsWith("term-202710") } }
        name.writeText(AppJson.catalog.encodeToString(TermCatalog.serializer(), catalog()))
        assertTrue(name.readText().contains("husky"))
        assertEquals(cleaned, emails(CatalogStore(directory).load("202710")!!))
    }

    @Test fun loadScrubsBundledCatalog() {
        val store = CatalogStore(tempDir()) { if (it == "202710") catalog() else null }
        assertEquals(cleaned, emails(store.load("202710")!!))
    }

    @Test fun bannerFacultyKeepsOnlyInstitutionalEmails() {
        fun email(raw: String?) = BannerFaculty("Doe, J", raw, true).toInstructor()!!.email
        assertNull(email("a@husky.neu.edu"))
        assertNull(email("b@example.com"))
        assertNull(email("c@neu.edu."))
        assertEquals("d2@NEU.edu", email("d@example.com, d2@NEU.edu"))
        assertEquals("e@northeastern.edu", email("e@northeastern.edu"))
    }

    @Test fun cachedInstructorsAreScrubbedWhenBannerReturnsNone() {
        val banner = BannerSection(courseReferenceNumber = "1", subject = "CS", courseNumber = "3000", meetingsFaculty = listOf(BannerMeetingFaculty()))
        val prior = CourseSection("1", "CS", "3000", instructors = dirty)
        val built = CatalogBuilder.makeSections(listOf(banner), mapOf("1" to prior)).single()
        assertEquals(cleaned, built.instructors!!.map { it.email })
    }
}
