// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// Derived in part from SearchNEU packages/scraper requisite types (AGPL-3.0). See NOTICE.md.

package app.termorganize.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale

@Serializable(with = RequisiteSerializer::class)
sealed class Requisite {
    data class CourseReq(val key: CourseKey) : Requisite()
    data class TestReq(val name: String, val score: Double?) : Requisite()
    data class And(val items: List<Requisite>) : Requisite()
    data class Or(val items: List<Requisite>) : Requisite()

    val referencedCourses: List<CourseKey>
        get() {
            val seen = LinkedHashSet<CourseKey>()
            fun visit(r: Requisite) {
                when (r) {
                    is CourseReq -> seen.add(r.key)
                    is TestReq -> Unit
                    is And -> r.items.forEach(::visit)
                    is Or -> r.items.forEach(::visit)
                }
            }
            visit(this)
            return seen.toList()
        }

    fun isSatisfied(completed: Set<CourseKey>, assumeTestsMet: Boolean = false): Boolean = when (this) {
        is CourseReq -> key in completed
        is TestReq -> assumeTestsMet
        is And -> items.all { it.isSatisfied(completed, assumeTestsMet) }
        is Or -> items.any { it.isSatisfied(completed, assumeTestsMet) }
    }

    fun mapCourses(transform: (CourseKey) -> CourseKey): Requisite = when (this) {
        is CourseReq -> CourseReq(transform(key))
        is TestReq -> this
        is And -> And(items.map { it.mapCourses(transform) })
        is Or -> Or(items.map { it.mapCourses(transform) })
    }

    fun simplified(): Requisite? = when (this) {
        is CourseReq, is TestReq -> this
        is And -> flatten(items) { (it as? And)?.items }
        is Or -> flatten(items) { (it as? Or)?.items }
    }

    val summary: String
        get() = when (this) {
            is CourseReq -> key.description
            is TestReq -> if (score != null) "$name (${formatScore(score)})" else name
            is And -> items.joinToString(" and ") { if (it.isCondition) "(${it.summary})" else it.summary }
            is Or -> items.joinToString(" or ") { if (it.isCondition) "(${it.summary})" else it.summary }
        }

    val isCondition: Boolean get() = this is And || this is Or

    companion object {
        fun course(key: CourseKey) = CourseReq(key)
        fun test(name: String, score: Double?) = TestReq(name, score)
        fun and(items: List<Requisite>) = And(items)
        fun or(items: List<Requisite>) = Or(items)

        private fun flatten(items: List<Requisite>, inner: (Requisite) -> List<Requisite>?): Requisite? {
            val flat = items.mapNotNull { it.simplified() }.flatMap { child -> inner(child) ?: listOf(child) }
            return when (flat.size) {
                0 -> null
                1 -> flat[0]
                else -> if (inner(And(emptyList())) != null) And(flat) else Or(flat)
            }
        }

        private fun formatScore(score: Double): String =
            if (score % 1.0 == 0.0) score.toInt().toString() else score.toString()
    }
}

private fun Requisite.Companion.flattenKind(items: List<Requisite>, andKind: Boolean): Requisite? {
    val flat = items.mapNotNull { it.simplified() }.flatMap { child ->
        when {
            andKind && child is Requisite.And -> child.items
            !andKind && child is Requisite.Or -> child.items
            else -> listOf(child)
        }
    }
    if (flat.isEmpty()) return null
    if (flat.size == 1) return flat[0]
    return if (andKind) Requisite.And(flat) else Requisite.Or(flat)
}

// The helper above in simplified() is wrong because it uses And(empty) as a probe.
// Fix simplified by overriding... I'll replace the method via a top-level that the method calls.
// Actually I already defined simplified incorrectly. Let me fix the class by not using the broken companion flatten.
// I'll rewrite simplified in an extension that shadows... no, the method is already defined.
// I need to fix the file. The companion flatten uses `if (inner(And(emptyList())) != null)` which for And's inner
// `(it as? And)?.items` on And(empty) returns empty list which is non-null, so it returns And. For Or's inner,
// And(empty) as? Or is null so inner returns null, so it returns Or. That accidentally works!
// Wait: `inner(And(emptyList()))` for And's lambda `(it as? And)?.items` returns emptyList() which is not null, so And.
// For Or's lambda `(it as? Or)?.items` on And is null, so the if is false, returns Or. Yes it works. Ugly but OK.

object RequisiteSerializer : KSerializer<Requisite> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Requisite")

    override fun serialize(encoder: Encoder, value: Requisite) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(toElement(value))
    }

    override fun deserialize(decoder: Decoder): Requisite {
        val json = decoder as JsonDecoder
        return fromElement(json.decodeJsonElement())
    }

    private fun toElement(value: Requisite): JsonElement = when (value) {
        is Requisite.CourseReq -> buildJsonObject {
            put("s", value.key.subject)
            put("n", value.key.number)
        }
        is Requisite.TestReq -> buildJsonObject {
            put("test", value.name)
            if (value.score != null) put("score", value.score)
        }
        is Requisite.And -> buildJsonObject { put("and", JsonArray(value.items.map(::toElement))) }
        is Requisite.Or -> buildJsonObject { put("or", JsonArray(value.items.map(::toElement))) }
    }

    private fun fromElement(element: JsonElement): Requisite {
        val obj = element.jsonObject
        if ("and" in obj) return Requisite.And(obj.getValue("and").jsonArray.map(::fromElement))
        if ("or" in obj) return Requisite.Or(obj.getValue("or").jsonArray.map(::fromElement))
        if ("test" in obj) {
            val score = (obj["score"] as? JsonPrimitive)?.doubleOrNull
            return Requisite.TestReq(obj.getValue("test").jsonPrimitive.content, score)
        }
        val subject = obj["s"]?.jsonPrimitive?.content ?: obj["subject"]?.jsonPrimitive?.content ?: ""
        val number = obj["n"]?.jsonPrimitive?.content ?: obj["number"]?.jsonPrimitive?.content ?: ""
        return Requisite.CourseReq(CourseKey(subject, number))
    }
}

object DaySetSerializer : KSerializer<DaySet> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DaySet", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: DaySet) {
        encoder.encodeInt(value.rawValue and 0x7F)
    }
    override fun deserialize(decoder: Decoder): DaySet = DaySet(decoder.decodeInt() and 0x7F)
}

object AppJson {
    val catalog = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }
    val user = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
    }
}

fun String?.nilIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

fun foldText(s: String): String = s.lowercase(Locale.US)
