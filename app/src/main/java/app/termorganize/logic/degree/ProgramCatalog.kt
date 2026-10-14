// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.degree

import app.termorganize.core.banner.HTMLText
import app.termorganize.core.model.CourseKey
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@Serializable
data class CatalogYear(val startYear: Int) : Comparable<CatalogYear> {
    val slug: String get() = "$startYear-${startYear + 1}"
    val label: String get() = "$startYear–${startYear + 1}"
    override fun compareTo(other: CatalogYear) = startYear.compareTo(other.startYear)
    override fun toString() = label

    companion object {
        fun parse(string: String): CatalogYear? {
            val match = Regex("""(\d{4})\s*[-–]\s*(\d{2,4})""").find(string) ?: return null
            val start = match.groupValues[1].toIntOrNull() ?: return null
            val end = match.groupValues[2].toIntOrNull() ?: return null
            if (end != start + 1 && end != (start + 1) % 100) return null
            return CatalogYear(start)
        }

        fun containing(epochMillis: Long, zone: ZoneId = ZoneId.of("America/New_York")): CatalogYear {
            val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
            val year = if (date.monthValue >= 7) date.year else date.year - 1
            return CatalogYear(year)
        }
    }
}

@Serializable
data class CatalogEditions(
    val current: CatalogYear,
    val archived: List<CatalogYear>,
    val fetchedAt: Long = 0,
    val isEstimated: Boolean = false,
) {
    val all: List<CatalogYear> get() = (listOf(current) + archived).distinct().sortedDescending()

    fun baseUrl(year: CatalogYear): String =
        if (year == current) SITE else "${SITE}archive/${year.slug}/"

    fun url(year: CatalogYear, path: String): String {
        val trimmed = path.removePrefix("/")
        return baseUrl(year) + trimmed
    }

    companion object {
        const val SITE = "https://catalog.northeastern.edu/"

        fun estimated(now: Long = System.currentTimeMillis()): CatalogEditions {
            val current = CatalogYear.containing(now)
            val archived = (current.startYear - 1 downTo 2016).map { CatalogYear(it) }
            return CatalogEditions(current, archived, 0, true)
        }
    }
}

@Serializable
data class CatalogProgram(val title: String, val path: String) {
    enum class Level { UNDERGRADUATE, GRADUATE, OTHER }
    val level: Level
        get() = when {
            path.startsWith("undergraduate/") -> Level.UNDERGRADUATE
            path.startsWith("graduate/") -> Level.GRADUATE
            else -> Level.OTHER
        }
    val name: String get() = parts().first
    val degree: String? get() = parts().second
    val campus: String? get() = parts().third
    val titleWithoutCampus: String get() = degree?.let { "$name, $it" } ?: title

    private fun parts(): Triple<String, String?, String?> {
        val match = Regex("""(.+?),\s*([A-Za-z][A-Za-z.]{1,9})(?:\s*\(([^)]+)\))?\s*""").matchEntire(title)
            ?: return Triple(title, null, null)
        return Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3].ifEmpty { null })
    }
}

@Serializable
data class UniversitywideRequirements(
    val totalHours: Double? = null,
    val gpa: Double? = null,
    val residencyHours: Double? = null,
    val requiresExperientialLearning: Boolean = false,
    val fetchedAt: Long = 0,
)

@Serializable
data class CourseOption(val alternatives: List<List<CourseKey>>) {
    val allCourses: List<CourseKey>
        get() {
            val seen = LinkedHashSet<CourseKey>()
            alternatives.flatten().forEach { seen += it }
            return seen.toList()
        }
    val label: String
        get() = alternatives.joinToString(" or ") { it.joinToString(" and ") { key -> key.description } }
}

@Serializable
data class CourseRange(
    val subject: String,
    val lowest: Int,
    val highest: Int? = null,
    val except: List<CourseKey> = emptyList(),
    val label: String,
) {
    fun contains(key: CourseKey): Boolean {
        if (key.subject != subject || key in except) return false
        val digits = key.number.takeWhile { it.isDigit() }
        if (digits.length != 4) return false
        val number = digits.toIntOrNull() ?: return false
        return number >= lowest && (highest == null || number <= highest)
    }
}

@Serializable
data class RequirementRule(
    val id: String,
    val kind: Kind,
    val label: String,
    val hours: Double? = null,
    val notes: List<String> = emptyList(),
    val optionCategories: List<String?>? = null,
) {
    @Serializable
    sealed class Kind {
        @Serializable
        data class Required(val option: CourseOption) : Kind()
        @Serializable
        data class Choose(val count: Int? = null, val hours: Double? = null, val options: List<CourseOption> = emptyList(), val ranges: List<CourseRange> = emptyList()) : Kind()
        @Serializable
        data class GeneralElectives(val hours: Double) : Kind()
    }

    val displayLabel: String get() = if (label.endsWith(":")) label.dropLast(1) else label
    val isGeneralElectives: Boolean get() = kind is Kind.GeneralElectives
    val listedCourses: List<CourseKey>
        get() = when (val k = kind) {
            is Kind.Required -> k.option.allCourses
            is Kind.Choose -> k.options.flatMap { it.allCourses }
            is Kind.GeneralElectives -> emptyList()
        }

    val degreeIsGeneralElectives: Boolean get() = kind is Kind.GeneralElectives

    /** The label names courses (`CS 3000`) rather than describing a choice. */
    val degreeNamesCourses: Boolean
        get() = Regex("""^[A-Z]{2,5}\s?\d{4}""").containsMatchIn(displayLabel)
}

@Serializable
data class RequirementGroup(
    val id: String,
    val title: String? = null,
    val rules: List<RequirementRule> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class RequirementSection(
    val id: String,
    val title: String,
    val isConcentration: Boolean = false,
    val groups: List<RequirementGroup> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val concentrationName: String
        get() = title
            .replace(Regex("""^(Concentration|Track|Option|Pathway)(\s+in\s+|\s*:\s*)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(Concentration|Track|Option|Pathway)$""", RegexOption.IGNORE_CASE), "")
            .trim('*', ' ')
}

@Serializable
data class ProgramRequirements(
    val year: CatalogYear,
    val program: CatalogProgram,
    val sections: List<RequirementSection>,
    val totalHours: Double? = null,
    val fetchedAt: Long = 0,
    val courseTitles: Map<String, String> = emptyMap(),
    val universitywide: UniversitywideRequirements? = null,
) {
    val concentrations: List<RequirementSection> get() = sections.filter { it.isConcentration }
    fun applicableSections(concentrationID: String?): List<RequirementSection> =
        sections.filter { !it.isConcentration || it.id == concentrationID }
    val allRules: List<RequirementRule> get() = sections.flatMap { it.groups.flatMap { g -> g.rules } }
    fun rule(id: String) = allRules.firstOrNull { it.id == id }
    fun titleOf(id: String): String? {
        for (section in sections) for (group in section.groups) {
            val rule = group.rules.firstOrNull { it.id == id } ?: continue
            if (group.rules.size == 1) return group.title ?: if (section.isConcentration) section.title else rule.displayLabel
            return rule.displayLabel
        }
        return null
    }
    fun titleFor(key: CourseKey): String? = courseTitles["${key.subject} ${key.number}"]

    data class RuleLocation(val section: RequirementSection, val group: RequirementGroup, val rule: RequirementRule)

    fun degreeLocation(id: String): RuleLocation? {
        for (section in sections) for (group in section.groups) {
            val rule = group.rules.firstOrNull { it.id == id } ?: continue
            return RuleLocation(section, group, rule)
        }
        return null
    }

    fun degreeShortName(id: String): String? {
        val location = degreeLocation(id) ?: return null
        if (location.group.rules.size == 1) {
            val title = titleOf(id)
            if (title != null) {
                if (location.section.isConcentration && location.group.title == null && !location.rule.degreeNamesCourses) {
                    return "${location.section.concentrationName} concentration"
                }
                return title
            }
        }
        if (location.rule.degreeNamesCourses) return location.rule.displayLabel
        location.group.title?.let { return it }
        if (location.section.isConcentration) return "${location.section.concentrationName} concentration"
        return location.section.title
    }

    fun degreeCountingName(id: String, course: CourseKey): String? {
        val name = degreeShortName(id) ?: return null
        if (!name.contains(course.description)) return name
        val location = degreeLocation(id) ?: return name
        location.group.title?.let { return it }
        return if (location.section.isConcentration) "${location.section.concentrationName} concentration" else location.section.title
    }

    fun degreeRule(id: String?, named: String?): RequirementRule? {
        if (id != null) rule(id)?.let { return it }
        val name = named?.lowercase(Locale.US) ?: return null
        val matches = allRules.filter { rule ->
            listOfNotNull(degreeShortName(rule.id), titleOf(rule.id)).any { it.lowercase(Locale.US) == name }
        }
        return if (matches.size == 1) matches[0] else null
    }
}

object ProgramCatalogParser {
    fun requirements(html: String, year: CatalogYear, program: CatalogProgram, fetchedAt: Long = System.currentTimeMillis()): ProgramRequirements? {
        val container = container(html) ?: return null
        val builder = Builder()
        for (token in tokens(container)) {
            when (token) {
                is Token.Heading -> builder.startSection(token.text)
                is Token.Paragraph -> builder.addParagraph(token.text)
                is Token.Table -> builder.addTable(token.html)
            }
        }
        val built = builder.finish()
        if (built.sections.isEmpty() && built.totalHours == null) return null
        return ProgramRequirements(year, program, built.sections, built.totalHours, fetchedAt, built.titles)
    }

    fun currentEdition(html: String): CatalogYear? {
        val text = HTMLText.decodeEntities(html)
        val patterns = listOf(Regex("""Academic Catalog\s+\d{4}\s*[-–]\s*\d{2,4}"""), Regex("""\d{4}\s*[-–]\s*\d{2,4}\s+Edition"""))
        for (pattern in patterns) {
            val found = pattern.find(text) ?: continue
            CatalogYear.parse(found.value)?.let { return it }
        }
        return null
    }

    fun archivedEditions(html: String): List<CatalogYear> {
        val years = LinkedHashSet<CatalogYear>()
        for (match in Regex("""archive/(\d{4})-(\d{4})""").findAll(html)) {
            val start = match.groupValues[1].toIntOrNull() ?: continue
            val end = match.groupValues[2].toIntOrNull() ?: continue
            if (end == start + 1) years += CatalogYear(start)
        }
        return years.sortedDescending()
    }

    fun universitywideLink(html: String): String? {
        for ((href, _) in links(html)) {
            if (!Regex("""(?i)university-?wide-?requirements""").containsMatchIn(href)) continue
            val path = editionRelativePath(href.substringBefore('#'))
            if (path.isEmpty()) continue
            return if (path.endsWith("/")) path else "$path/"
        }
        return null
    }

    fun universitywideRequirements(html: String, fetchedAt: Long = System.currentTimeMillis()): UniversitywideRequirements? {
        val text = HTMLText.plainText(requirementsContainer(html))
        fun number(pattern: Regex) = pattern.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val parsed = UniversitywideRequirements(
            totalHours = number(Regex("""(?i)minimum\s+(?:of\s+)?(\d+(?:\.\d+)?)\s+total\s+semester\s+hours""")),
            gpa = number(Regex("""(?i)minimum\s+(?:cumulative\s+)?(\d\.\d+)\s+GPA""")),
            residencyHours = number(Regex("""(?i)minimum\s+of\s+(\d+(?:\.\d+)?)\s+Northeastern\s+University\s+semester\s+hours""")),
            requiresExperientialLearning = Regex("""(?i)experiential\s+learning\s+graduation\s+requirement""").containsMatchIn(text),
            fetchedAt = fetchedAt,
        )
        if (parsed.totalHours == null && parsed.gpa == null && parsed.residencyHours == null && !parsed.requiresExperientialLearning) return null
        return parsed
    }

    private fun requirementsContainer(html: String): String {
        val start = Regex("""<div[^>]*id="programrequirementstextcontainer"""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<div[^>]*id="textcontainer"""", RegexOption.IGNORE_CASE).find(html)
            ?: return html
        val rest = html.substring(start.range.last + 1)
        val next = Regex("""<div[^>]*id="[a-z]+textcontainer"""", RegexOption.IGNORE_CASE).find(rest)
        if (next != null) return rest.substring(0, next.range.first)
        val footer = Regex("<footer", RegexOption.IGNORE_CASE).find(rest)
        if (footer != null) return rest.substring(0, footer.range.first)
        return rest
    }

    fun programs(html: String): List<CatalogProgram> {
        val byPath = LinkedHashMap<String, CatalogProgram>()
        for ((href, label) in links(html)) {
            val path = editionRelativePath(href)
            if (!(path.startsWith("undergraduate/") || path.startsWith("graduate/")) || !path.endsWith("/")) continue
            val title = HTMLText.plainText(label)
            val program = CatalogProgram(title, path)
            val degree = program.degree ?: continue
            if (degree.firstOrNull()?.isUpperCase() != true) continue
            if (degree.count { it.isUpperCase() } < 2) continue
            if (path !in byPath) byPath[path] = program
        }
        return byPath.values.sortedBy { it.title.lowercase(Locale.US) }
    }

    fun suggestedPrograms(auditCode: String?, programs: List<CatalogProgram>): List<CatalogProgram> {
        val degree = auditCode?.split('-')?.lastOrNull()?.takeIf { it.length >= 2 } ?: return emptyList()
        return programs.filter { it.degree.equals(degree, true) }.sortedWith(
            compareBy<CatalogProgram> {
                when (it.campus) {
                    null -> 0
                    "Boston" -> 1
                    else -> 2
                }
            }.thenBy { it.title },
        )
    }

    fun editionRelativePath(href: String): String {
        var path = href
        val url = runCatching { java.net.URI(href) }.getOrNull()
        if (url != null && url.host != null) {
            path = url.path + if (href.endsWith("/") && !url.path.endsWith("/")) "/" else ""
        }
        path = path.replace(Regex("""^/?archive/\d{4}-\d{4}/?"""), "")
        return path.removePrefix("/")
    }

    private fun container(html: String): String? {
        val match = Regex("""id="programrequirementstextcontainer"([\s\S]*)$""", RegexOption.IGNORE_CASE).find(html) ?: return null
        val rest = match.groupValues[1]
        val end = Regex("""<div[^>]+id="(?!programrequirementstextcontainer)""", RegexOption.IGNORE_CASE).find(rest)
        return if (end == null) rest else rest.substring(0, end.range.first)
    }

    private sealed class Token {
        data class Heading(val text: String) : Token()
        data class Paragraph(val text: String) : Token()
        data class Table(val html: String) : Token()
    }

    private fun tokens(html: String): List<Token> {
        val pattern = Regex("""<h([2-4])[^>]*>([\s\S]*?)</h\1>|<table[^>]*class="[^"]*sc_courselist[^"]*"[^>]*>([\s\S]*?)</table>|<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
        return pattern.findAll(html).mapNotNull { match ->
            val heading = match.groups[2]?.value
            val table = match.groups[3]?.value
            val paragraph = match.groups[4]?.value
            when {
                heading != null -> Token.Heading(HTMLText.plainText(heading))
                table != null -> Token.Table(table)
                paragraph != null -> {
                    val text = HTMLText.plainText(paragraph)
                    if (text.isEmpty()) null else Token.Paragraph(text)
                }
                else -> null
            }
        }.toList()
    }

    data class Row(val kind: Kind, val hours: Double? = null, val titles: List<String> = emptyList()) {
        sealed class Kind {
            data object Skip : Kind()
            data class AreaHeader(val title: String) : Kind()
            data class Subheader(val title: String) : Kind()
            data class Comment(val text: String) : Kind()
            data class Courses(val sets: List<List<CourseKey>>, val isAlternative: Boolean) : Kind()
        }
    }

    fun rows(table: String): List<Row> {
        val rowPattern = Regex("""<tr([^>]*)>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        return rowPattern.findAll(table).map { parseRow(it.groupValues[1], it.groupValues[2]) }.toList()
    }

    private fun parseRow(attributes: String, body: String): Row {
        val rowClass = Regex("""class="([^"]*)"""").find(attributes)?.groupValues?.get(1) ?: ""
        val cellPattern = Regex("""<t([dh])([^>]*)>([\s\S]*?)</t[dh]>""", RegexOption.IGNORE_CASE)
        val cells = cellPattern.findAll(body).map {
            Triple(it.groupValues[1].equals("h", true), Regex("""class="([^"]*)"""").find(it.groupValues[2])?.groupValues?.get(1) ?: "", it.groupValues[3])
        }.toList()
        val hours = cells.firstOrNull { it.second.contains("hourscol") }?.let { hoursValue(HTMLText.plainText(it.third)) }
        if ("hidden" in rowClass || cells.any { it.first }) return Row(Row.Kind.Skip)
        if ("areasubheader" in rowClass || "areasubheader" in body) return Row(Row.Kind.Subheader(HTMLText.plainText(body)), hours)
        if ("areaheader" in rowClass || "courselistcomment areaheader" in body) return Row(Row.Kind.AreaHeader(HTMLText.plainText(body)), hours)
        val code = cells.firstOrNull { it.second.contains("codecol") }
        if (code != null) {
            val text = spacedText(code.third)
            val isAlternative = "orclass" in rowClass || "orclass" in code.second || text.lowercase(Locale.US).startsWith("or ")
            val sets = courseSets(text)
            if (sets.isNotEmpty()) {
                val titleCell = cells.firstOrNull { !it.second.contains("codecol") && !it.second.contains("hourscol") && !it.first }
                val titles = titleCell?.let { HTMLText.plainText(it.third) }?.split('\n')?.map { line ->
                    val trimmed = line.trim()
                    if (trimmed.lowercase(Locale.US).startsWith("and ")) trimmed.drop(4) else trimmed
                }?.filter { it.isNotEmpty() } ?: emptyList()
                return Row(Row.Kind.Courses(sets, isAlternative), hours, titles)
            }
        }
        if ("courselistcomment" in body) {
            val text = spacedText(cells.firstOrNull()?.third ?: body)
            return if (text.isEmpty()) Row(Row.Kind.Skip) else Row(Row.Kind.Comment(text), hours)
        }
        return Row(Row.Kind.Skip)
    }

    private fun spacedText(html: String): String =
        HTMLText.plainText(html.replace("<", " <"))
            .replace(Regex("""\s+([,.:;])"""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun courseSets(text: String): List<List<CourseKey>> {
        var cleaned = text.replace('\u00A0', ' ')
        if (cleaned.lowercase(Locale.US).startsWith("or ")) cleaned = cleaned.drop(3)
        return cleaned.split(" or ").mapNotNull { part ->
            val keys = courseKeys(part)
            keys.ifEmpty { null }
        }
    }

    fun courseKeys(text: String): List<CourseKey> =
        Regex("""\b([A-Z]{2,5})\s+(\d{4}[A-Z]?)\b""").findAll(text).map { CourseKey(it.groupValues[1], it.groupValues[2]) }.toList()

    fun hoursValue(text: String): Double? = Regex("""\d+(?:\.\d+)?""").find(text)?.value?.toDoubleOrNull()

    fun courseRange(text: String): CourseRange? {
        val cleaned = text.replace('\u00A0', ' ').trim()
        val higher = Regex("""([A-Z]{2,5}) (\d{4}) or (?:higher|above)(?:,? (?:except|excluding|but not) (.+?))?\.?""").matchEntire(cleaned)
        if (higher != null) {
            val lowest = higher.groupValues[2].toIntOrNull() ?: return null
            val except = if (higher.groupValues[3].isEmpty()) emptyList() else courseKeys(higher.groupValues[3])
            return CourseRange(higher.groupValues[1], lowest, null, except, cleaned)
        }
        val span = Regex("""([A-Z]{2,5}) (\d{4}) (?:to|through|-|–) (?:([A-Z]{2,5}) )?(\d{4})(?:,? (?:except|excluding|but not) (.+?))?\.?""").matchEntire(cleaned)
        if (span != null) {
            val subject = span.groupValues[1]
            val other = span.groupValues[3]
            if (other.isNotEmpty() && other != subject) return null
            val lowest = span.groupValues[2].toIntOrNull() ?: return null
            val highest = span.groupValues[4].toIntOrNull() ?: return null
            val except = if (span.groupValues[5].isEmpty()) emptyList() else courseKeys(span.groupValues[5])
            return CourseRange(subject, lowest, highest, except, cleaned)
        }
        return null
    }

    fun chooseAmount(text: String): Pair<Int?, Double?>? {
        val lower = text.lowercase(Locale.US)
        if (!lower.startsWith("complete") && !lower.startsWith("choose") && !lower.startsWith("select") && !lower.startsWith("take")) return null
        val hours = Regex("""(\d+(?:\.\d+)?)(?:\s*[-–]\s*\d+)?\s+(?:semester hours?|credits?|credit hours?|sh)\b""").find(lower)
        if (hours != null) return null to hours.groupValues[1].toDouble()
        val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12)
        val count = Regex("""^(?:complete|choose|select|take)\s+(?:any\s+)?(\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\b""").find(lower)
        if (count != null) {
            val token = count.groupValues[1]
            val n = token.toIntOrNull() ?: words[token] ?: return null
            return n to null
        }
        return null
    }

    private class Builder {
        data class RuleDraft(
            var kind: Kind,
            val label: String,
            val hours: Double?,
            val notes: MutableList<String> = mutableListOf(),
            val optionCategories: MutableList<String?> = mutableListOf(),
            var currentCategory: String? = null,
        ) {
            sealed class Kind {
                data class Required(val alternatives: List<List<CourseKey>>) : Kind()
                data class Choose(val count: Int?, val hours: Double?, val options: List<List<List<CourseKey>>>, val ranges: List<CourseRange>) : Kind()
                data class General(val hours: Double) : Kind()
            }
        }
        data class GroupDraft(val title: String?, val rules: MutableList<RuleDraft> = mutableListOf(), val notes: MutableList<String> = mutableListOf())
        data class SectionDraft(val title: String, val groups: MutableList<GroupDraft> = mutableListOf(), val notes: MutableList<String> = mutableListOf())
        data class Built(val sections: List<RequirementSection>, val totalHours: Double?, val titles: Map<String, String>)

        val sections = mutableListOf<SectionDraft>()
        var totalHours: Double? = null
        val titles = LinkedHashMap<String, String>()
        var chooseIndex: Int? = null

        fun startSection(title: String) {
            sections += SectionDraft(title.trim())
            chooseIndex = null
        }

        fun addParagraph(text: String) {
            Regex("""(\d+(?:\.\d+)?)\s+total semester hours""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                totalHours = maxOf(totalHours ?: 0.0, it)
            }
            if (sections.isNotEmpty()) sections.last().notes += text
        }

        fun addTable(html: String) {
            if (sections.isEmpty()) startSection("Program Requirements")
            chooseIndex = null
            var started = false
            for (row in rows(html)) {
                when (val kind = row.kind) {
                    Row.Kind.Skip -> Unit
                    is Row.Kind.AreaHeader -> { appendGroup(kind.title); started = true }
                    is Row.Kind.Subheader -> {
                        val index = chooseIndex
                        if (index != null) {
                            group().rules[index].notes += kind.title
                            group().rules[index].currentCategory = kind.title
                        } else group().notes += kind.title
                    }
                    is Row.Kind.Comment -> {
                        if (!started) { appendGroup(null); started = true }
                        addComment(kind.text, row.hours)
                    }
                    is Row.Kind.Courses -> {
                        if (!started) { appendGroup(null); started = true }
                        addCourses(kind.sets, kind.isAlternative, row.hours)
                        for ((key, title) in kind.sets.flatten().zip(row.titles)) {
                            val id = key.description
                            if (id !in titles) titles[id] = title
                        }
                    }
                }
            }
        }

        private fun appendGroup(title: String?) {
            sections.last().groups += GroupDraft(title)
            chooseIndex = null
        }

        private fun group() = sections.last().groups.last()

        private fun addComment(text: String, hours: Double?) {
            if ("general elective" in text.lowercase(Locale.US)) {
                val amount = hours ?: chooseAmount(text)?.second
                if (amount != null) {
                    group().rules += RuleDraft(RuleDraft.Kind.General(amount), text, amount)
                    chooseIndex = null
                    return
                }
            }
            val range = courseRange(text)
            val index = chooseIndex
            if (range != null && index != null) {
                val rule = group().rules[index]
                val kind = rule.kind
                if (kind is RuleDraft.Kind.Choose) {
                    rule.kind = kind.copy(ranges = kind.ranges + range)
                    return
                }
            }
            if (range != null && index == null) {
                group().notes += text
                return
            }
            val amount = chooseAmount(text)
            if (amount != null) {
                group().rules += RuleDraft(RuleDraft.Kind.Choose(amount.first, amount.second, emptyList(), emptyList()), text, hours ?: amount.second)
                chooseIndex = group().rules.lastIndex
                return
            }
            if (index != null) group().rules[index].notes += text else group().notes += text
        }

        private fun addCourses(sets: List<List<CourseKey>>, isAlternative: Boolean, hours: Double?) {
            val index = chooseIndex
            if (isAlternative && index != null) {
                val rule = group().rules[index]
                val kind = rule.kind
                if (kind is RuleDraft.Kind.Choose && kind.options.isNotEmpty()) {
                    val options = kind.options.toMutableList()
                    options[options.lastIndex] = options.last() + sets
                    rule.kind = kind.copy(options = options)
                    return
                }
            }
            if (isAlternative) {
                val last = group().rules.lastOrNull()
                val kind = last?.kind
                if (kind is RuleDraft.Kind.Required) {
                    last.kind = RuleDraft.Kind.Required(kind.alternatives + sets)
                    return
                }
            }
            if (index != null && hours == null) {
                val rule = group().rules[index]
                val kind = rule.kind
                if (kind is RuleDraft.Kind.Choose) {
                    rule.kind = kind.copy(options = kind.options + listOf(sets))
                    rule.optionCategories += rule.currentCategory
                    return
                }
            }
            group().rules += RuleDraft(RuleDraft.Kind.Required(sets), "", hours)
            chooseIndex = null
        }

        fun finish(): Built {
            val sectionIds = HashSet<String>()
            val built = sections.map { draft ->
                val sectionId = unique(slug(draft.title), sectionIds)
                val groupIds = HashSet<String>()
                val groups = draft.groups.mapNotNull { group ->
                    val groupId = unique("$sectionId/${slug(group.title ?: "courses")}", groupIds)
                    val ruleIds = HashSet<String>()
                    val rules = group.rules.mapNotNull { rule ->
                        val kind: RequirementRule.Kind
                        var label = rule.label
                        when (val k = rule.kind) {
                            is RuleDraft.Kind.Required -> {
                                if (k.alternatives.isEmpty()) return@mapNotNull null
                                val option = CourseOption(k.alternatives)
                                kind = RequirementRule.Kind.Required(option)
                                label = option.label
                            }
                            is RuleDraft.Kind.Choose -> kind = RequirementRule.Kind.Choose(k.count, k.hours, k.options.map { CourseOption(it) }, k.ranges)
                            is RuleDraft.Kind.General -> kind = RequirementRule.Kind.GeneralElectives(k.hours)
                        }
                        val id = unique("$groupId/${slug(label)}", ruleIds)
                        val categories = if (rule.optionCategories.any { it != null }) rule.optionCategories else null
                        RequirementRule(id, kind, label, rule.hours, rule.notes, categories)
                    }
                    if (rules.isEmpty() && group.notes.isEmpty()) null
                    else RequirementGroup(groupId, group.title, rules, group.notes)
                }
                val title = draft.title.trim('*', ' ')
                val concentration = groups.isNotEmpty() && Regex("""^(concentration|track|option|pathway)( in\b|\s*:)|\b(concentration|track|option|pathway)$""", RegexOption.IGNORE_CASE).containsMatchIn(title)
                RequirementSection(sectionId, title, concentration, groups, draft.notes)
            }
            return Built(built, totalHours, titles)
        }

        private fun slug(text: String): String {
            val lowered = text.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            return lowered.take(60).trim('-')
        }

        private fun unique(id: String, used: MutableSet<String>): String {
            var candidate = id.ifEmpty { "item" }
            var n = 2
            while (!used.add(candidate)) {
                candidate = "$id-$n"
                n++
            }
            return candidate
        }
    }

    /**
     * One directory level under [parentPath]. The undergraduate index lists colleges,
     * and a college page lists departments. Degree titles live on the department page.
     */
    fun childDirectories(html: String, parentPath: String): List<String> {
        val parent = parentPath.removePrefix("/").let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }
        if (parent.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for ((href, _) in links(html)) {
            val path = editionRelativePath(href)
            if (!path.startsWith(parent) || path == parent || !path.endsWith("/")) continue
            val rest = path.removePrefix(parent).trim('/')
            if (rest.isEmpty() || rest.contains('/')) continue
            if (rest in skippedDirectories) continue
            out += path
        }
        return out.toList()
    }

    private val skippedDirectories = setOf(
        "admission", "information-entering-students", "expenses", "academic-policies-procedures",
        "university-academics", "general-information", "handbook", "course-descriptions", "archive",
    )

    private fun links(html: String): List<Pair<String, String>> =
        Regex("""<a\s[^>]*href="([^"#]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
}

/**
 * The public catalog index does not list degrees. Colleges link to departments,
 * and department pages link to "Computer Science, BSCS".
 */
object ProgramCatalogWalker {
    fun programs(startUrl: String, fetch: (String) -> String): List<CatalogProgram> =
        collect(startUrl, fetch, 0).distinctBy { it.path }.sortedBy { it.title.lowercase(Locale.US) }

    private fun collect(url: String, fetch: (String) -> String, depth: Int): List<CatalogProgram> {
        if (depth > 3) return emptyList()
        val html = runCatching { fetch(url) }.getOrElse { return emptyList() }
        val listed = ProgramCatalogParser.programs(html)
        if (listed.isNotEmpty()) return listed
        if (depth == 3) return emptyList()
        val parent = java.net.URI(url).path
        return ProgramCatalogParser.childDirectories(html, parent).flatMap { child ->
            collect(CatalogEditions.SITE + child.removePrefix("/"), fetch, depth + 1)
        }
    }
}
