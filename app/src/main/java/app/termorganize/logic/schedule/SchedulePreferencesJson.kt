// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.logic.schedule

import app.termorganize.core.model.DaySet
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Tolerant JSON for [SchedulePreferences]. A damaged or newer value becomes the default for that
 * field instead of dropping the saved schedule. Names match the Swift raw values (`morning`,
 * `fewerDays`, `waitlistOK`).
 */
object SchedulePreferencesSerializer : KSerializer<SchedulePreferences> {
    override val descriptor = buildClassSerialDescriptor("SchedulePreferences")

    override fun serialize(encoder: Encoder, value: SchedulePreferences) {
        val json = encoder as? JsonEncoder ?: return
        json.encodeJsonElement(encode(value))
    }

    override fun deserialize(decoder: Decoder): SchedulePreferences {
        val json = decoder as? JsonDecoder ?: return SchedulePreferences()
        val element = runCatching { json.decodeJsonElement() }.getOrNull() ?: return SchedulePreferences()
        return decode(element)
    }

    fun encode(value: SchedulePreferences): JsonObject = buildJsonObject {
        if (value.timeOfDay.isNotEmpty()) {
            put("timeOfDay", buildJsonObject {
                value.timeOfDay.forEach { (band, preference) -> put(band.wire(), preference.wire()) }
            })
        }
        value.earliestStart?.let { put("earliestStart", limit(it)) }
        value.latestEnd?.let { put("latestEnd", limit(it)) }
        put("daysOff", value.daysOff.rawValue and 0x7F)
        put("daysOffStrength", value.daysOffStrength.wire())
        put("dayLayout", value.dayLayout.wire())
        value.maxClassesPerDay?.let { put("maxClassesPerDay", it) }
        value.maxMinutesPerDay?.let { put("maxMinutesPerDay", it) }
        put("gaps", value.gaps.wire())
        put("breaks", value.breaks.wire())
        put("minimumBreakMinutes", value.minimumBreakMinutes)
        put("minimumBreakStrength", value.minimumBreakStrength.wire())
        value.lunch?.let { put("lunch", lunch(it)) }
        put("blockedTimes", buildJsonArray { value.blockedTimes.forEach { add(block(it)) } })
        put("format", value.format.wire())
        put("formatStrength", value.formatStrength.wire())
        value.campus?.let { put("campus", it) }
        put("campusStrength", value.campusStrength.wire())
        put("seats", value.seats.wire())
        put("preferredInstructors", strings(value.preferredInstructors))
        put("avoidedInstructors", strings(value.avoidedInstructors))
        value.creditTarget?.let { put("creditTarget", buildJsonObject { put("lower", it.lower); put("upper", it.upper) }) }
    }

    fun decode(element: JsonElement): SchedulePreferences {
        val obj = element as? JsonObject ?: return SchedulePreferences()
        val times = LinkedHashMap<TimeBand, TimePreference>()
        (obj["timeOfDay"] as? JsonObject)?.forEach { (bandName, preference) ->
            val band = TimeBand.fromWire(bandName) ?: return@forEach
            val choice = (preference as? JsonPrimitive)?.contentOrNull()?.let { TimePreference.fromWire(it) } ?: return@forEach
            if (choice != TimePreference.OKAY) times[band] = choice
        }
        return SchedulePreferences(
            timeOfDay = times,
            earliestStart = limit(obj["earliestStart"]),
            latestEnd = limit(obj["latestEnd"]),
            daysOff = DaySet((obj["daysOff"].intOrNull() ?: 0) and 0x7F),
            daysOffStrength = enum(obj["daysOffStrength"], PreferenceStrength::fromWire) ?: PreferenceStrength.PREFERRED,
            dayLayout = enum(obj["dayLayout"], DayLayout::fromWire) ?: DayLayout.NO_PREFERENCE,
            maxClassesPerDay = obj["maxClassesPerDay"].intOrNull(),
            maxMinutesPerDay = obj["maxMinutesPerDay"].intOrNull(),
            gaps = enum(obj["gaps"], GapPreference::fromWire) ?: GapPreference.NO_PREFERENCE,
            breaks = enum(obj["breaks"], BreakPreference::fromWire) ?: BreakPreference.FINE,
            minimumBreakMinutes = obj["minimumBreakMinutes"].intOrNull() ?: 15,
            minimumBreakStrength = enum(obj["minimumBreakStrength"], PreferenceStrength::fromWire) ?: PreferenceStrength.PREFERRED,
            lunch = lunch(obj["lunch"]),
            blockedTimes = (obj["blockedTimes"] as? JsonArray)?.mapNotNull { block(it) }.orEmpty(),
            format = enum(obj["format"], FormatPreference::fromWire) ?: FormatPreference.NO_PREFERENCE,
            formatStrength = enum(obj["formatStrength"], PreferenceStrength::fromWire) ?: PreferenceStrength.PREFERRED,
            campus = obj["campus"].stringOrNull(),
            campusStrength = enum(obj["campusStrength"], PreferenceStrength::fromWire) ?: PreferenceStrength.PREFERRED,
            seats = enum(obj["seats"], SeatPolicy::fromWire) ?: SeatPolicy.ANY,
            preferredInstructors = strings(obj["preferredInstructors"]),
            avoidedInstructors = strings(obj["avoidedInstructors"]),
            creditTarget = credit(obj["creditTarget"]),
        )
    }

    private fun limit(value: TimeLimit) = buildJsonObject {
        put("minutes", value.minutes)
        put("strength", value.strength.wire())
    }

    private fun limit(element: JsonElement?): TimeLimit? {
        val obj = element as? JsonObject ?: return null
        val minutes = obj["minutes"].intOrNull() ?: return null
        return TimeLimit(minutes, enum(obj["strength"], PreferenceStrength::fromWire) ?: PreferenceStrength.REQUIRED)
    }

    private fun lunch(value: LunchBreak) = buildJsonObject {
        put("start", value.start)
        put("end", value.end)
        put("minutes", value.minutes)
    }

    private fun lunch(element: JsonElement?): LunchBreak? {
        val obj = element as? JsonObject ?: return null
        return LunchBreak(
            obj["start"].intOrNull() ?: (11 * 60 + 30),
            obj["end"].intOrNull() ?: (14 * 60),
            obj["minutes"].intOrNull() ?: 30,
        )
    }

    private fun block(value: BlockedTime) = buildJsonObject {
        put("id", value.id)
        put("label", value.label)
        put("days", value.days.rawValue and 0x7F)
        put("start", value.start)
        put("end", value.end)
        put("strength", value.strength.wire())
        put("isEnabled", value.isEnabled)
    }

    private fun block(element: JsonElement): BlockedTime? {
        val obj = element as? JsonObject ?: return null
        return BlockedTime(
            id = obj["id"].stringOrNull() ?: UUID.randomUUID().toString(),
            label = obj["label"].stringOrNull() ?: "",
            days = DaySet(obj["days"].intOrNull() ?: 0),
            start = obj["start"].intOrNull() ?: 0,
            end = obj["end"].intOrNull() ?: 0,
            strength = enum(obj["strength"], PreferenceStrength::fromWire) ?: PreferenceStrength.REQUIRED,
            isEnabled = obj["isEnabled"].boolOrNull() ?: true,
        )
    }

    private fun credit(element: JsonElement?): CreditTarget? {
        val obj = element as? JsonObject ?: return null
        val lower = obj["lower"].intOrNull() ?: return null
        val upper = obj["upper"].intOrNull() ?: return null
        return CreditTarget(lower, upper)
    }

    private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun strings(element: JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.map { item -> item.stringOrNull() ?: return emptyList() }
    }

    private fun <T> enum(element: JsonElement?, parse: (String) -> T?): T? =
        element.stringOrNull()?.let(parse)
}

private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.contentOrNull()?.toIntOrNull()
private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.boolOrNull(): Boolean? = (this as? JsonPrimitive)?.contentOrNull()?.toBooleanStrictOrNull()
private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content

private fun TimeBand.wire(): String = when (this) {
    TimeBand.EARLY_MORNING -> "earlyMorning"
    TimeBand.MORNING -> "morning"
    TimeBand.MIDDAY -> "midday"
    TimeBand.AFTERNOON -> "afternoon"
    TimeBand.EVENING -> "evening"
    TimeBand.NIGHT -> "night"
}

private fun TimeBand.Companion.fromWire(raw: String): TimeBand? = when (raw) {
    "earlyMorning", "EARLY_MORNING" -> TimeBand.EARLY_MORNING
    "morning", "MORNING" -> TimeBand.MORNING
    "midday", "MIDDAY" -> TimeBand.MIDDAY
    "afternoon", "AFTERNOON" -> TimeBand.AFTERNOON
    "evening", "EVENING" -> TimeBand.EVENING
    "night", "NIGHT" -> TimeBand.NIGHT
    else -> null
}

private fun TimePreference.wire(): String = when (this) {
    TimePreference.AVOID -> "avoid"
    TimePreference.OKAY -> "okay"
    TimePreference.PREFER -> "prefer"
}

private fun TimePreference.Companion.fromWire(raw: String): TimePreference? = when (raw) {
    "avoid", "AVOID" -> TimePreference.AVOID
    "okay", "OKAY" -> TimePreference.OKAY
    "prefer", "PREFER" -> TimePreference.PREFER
    else -> null
}

private fun PreferenceStrength.wire(): String = when (this) {
    PreferenceStrength.PREFERRED -> "preferred"
    PreferenceStrength.REQUIRED -> "required"
}

private fun PreferenceStrength.Companion.fromWire(raw: String): PreferenceStrength? = when (raw) {
    "preferred", "PREFERRED" -> PreferenceStrength.PREFERRED
    "required", "REQUIRED" -> PreferenceStrength.REQUIRED
    else -> null
}

private fun DayLayout.wire(): String = when (this) {
    DayLayout.NO_PREFERENCE -> "noPreference"
    DayLayout.FEWER_DAYS -> "fewerDays"
    DayLayout.SPREAD_OUT -> "spreadOut"
}

private fun DayLayout.Companion.fromWire(raw: String): DayLayout? = when (raw) {
    "noPreference", "NO_PREFERENCE" -> DayLayout.NO_PREFERENCE
    "fewerDays", "FEWER_DAYS" -> DayLayout.FEWER_DAYS
    "spreadOut", "SPREAD_OUT" -> DayLayout.SPREAD_OUT
    else -> null
}

private fun GapPreference.wire(): String = when (this) {
    GapPreference.COMPACT -> "compact"
    GapPreference.NO_PREFERENCE -> "noPreference"
    GapPreference.FINE -> "fine"
}

private fun GapPreference.Companion.fromWire(raw: String): GapPreference? = when (raw) {
    "compact", "COMPACT" -> GapPreference.COMPACT
    "noPreference", "NO_PREFERENCE" -> GapPreference.NO_PREFERENCE
    "fine", "FINE" -> GapPreference.FINE
    else -> null
}

private fun BreakPreference.wire(): String = when (this) {
    BreakPreference.FINE -> "fine"
    BreakPreference.AVOID_BACK_TO_BACK -> "avoidBackToBack"
    BreakPreference.AT_LEAST -> "atLeast"
}

private fun BreakPreference.Companion.fromWire(raw: String): BreakPreference? = when (raw) {
    "fine", "FINE" -> BreakPreference.FINE
    "avoidBackToBack", "AVOID_BACK_TO_BACK" -> BreakPreference.AVOID_BACK_TO_BACK
    "atLeast", "AT_LEAST" -> BreakPreference.AT_LEAST
    else -> null
}

private fun FormatPreference.wire(): String = when (this) {
    FormatPreference.NO_PREFERENCE -> "noPreference"
    FormatPreference.IN_PERSON -> "inPerson"
    FormatPreference.HYBRID -> "hybrid"
    FormatPreference.ONLINE -> "online"
}

private fun FormatPreference.Companion.fromWire(raw: String): FormatPreference? = when (raw) {
    "noPreference", "NO_PREFERENCE" -> FormatPreference.NO_PREFERENCE
    "inPerson", "IN_PERSON" -> FormatPreference.IN_PERSON
    "hybrid", "HYBRID" -> FormatPreference.HYBRID
    "online", "ONLINE" -> FormatPreference.ONLINE
    else -> null
}

private fun SeatPolicy.wire(): String = when (this) {
    SeatPolicy.ANY -> "any"
    SeatPolicy.WAITLIST_OK -> "waitlistOK"
    SeatPolicy.OPEN_ONLY -> "openOnly"
}

private fun SeatPolicy.Companion.fromWire(raw: String): SeatPolicy? = when (raw) {
    "any", "ANY" -> SeatPolicy.ANY
    "waitlistOK", "WAITLIST_OK" -> SeatPolicy.WAITLIST_OK
    "openOnly", "OPEN_ONLY" -> SeatPolicy.OPEN_ONLY
    else -> null
}
