// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only
// A small PDF 1.4 writer/reader so degree-plan export matches the audit layout
// and round-trips in JVM unit tests without Android or AWT.

package app.termorganize.logic.degree

import java.nio.charset.StandardCharsets
import java.util.Locale

object DegreePlanPdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val LINE = 18
    private const val FIRST = 54
    private const val LAST = 774
    private val INDENT = intArrayOf(36, 96, 126)

    fun render(plan: DegreePlan, printedAt: Long = System.currentTimeMillis(), includeStudent: Boolean): ByteArray {
        data class Line(val indent: Int, val text: String, val header: Boolean)
        val blank = Line(0, " ", false)
        val lines = mutableListOf(blank, blank)
        DegreePlanFormat.headerLines(plan, printedAt, includeStudent).forEach { lines += Line(0, it, true) }
        DegreePlanFormat.bodyLines(plan).forEach { lines += Line(it.first, it.second, false) }
        lines += blank
        lines += blank
        val lastVisible = lines.indexOfLast { it.text != " " }
        val pages = mutableListOf<StringBuilder>()
        var page = StringBuilder()
        var baseline = LAST + 1
        var open = false
        for ((index, line) in lines.withIndex()) {
            if (baseline > LAST) {
                if (index > lastVisible) break
                if (open) pages += page
                page = StringBuilder()
                open = true
                baseline = FIRST
            }
            val y = PAGE_H - baseline
            val shown = line.text.replace('\t', ' ')
            val font = if (line.header) "F2" else "F1"
            page.append(draw(shown, font, INDENT[line.indent], y))
            if (line.indent > 0) page.append(draw("-", "F1", INDENT[line.indent] - 30, y))
            baseline += LINE
        }
        if (open) pages += page
        return assemble(pages.map { it.toString() }, plan.name)
    }

    fun textFrom(data: ByteArray): String? {
        val raw = data.toString(StandardCharsets.ISO_8859_1)
        if (!raw.startsWith("%PDF-")) return null
        val streams = Regex("stream\\r?\\n([\\s\\S]*?)\\r?\\nendstream").findAll(raw).map { it.groupValues[1] }.toList()
        if (streams.isEmpty()) return null
        val lines = mutableListOf<String>()
        val op = Regex("""\(((?:\\.|[^)\\])*)\)\s*Tj""")
        for (stream in streams) {
            for (match in op.findAll(stream)) {
                lines += unescape(match.groupValues[1])
            }
        }
        val text = lines.joinToString("\n")
        return text.ifEmpty { null }
    }

    private fun draw(text: String, font: String, x: Int, y: Int): String {
        if (text.isEmpty()) return ""
        return "BT /$font 12 Tf 1 0 0 1 $x $y Tm (${escape(text)}) Tj ET\n"
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    private fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) {
                out.append(text[i + 1])
                i += 2
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun assemble(pages: List<String>, title: String): ByteArray {
        val objects = mutableListOf<ByteArray>()
        fun add(s: String) { objects += s.toByteArray(StandardCharsets.ISO_8859_1) }
        val nPages = pages.size
        val font1 = 3 + nPages * 2
        val font2 = font1 + 1
        add("<< /Type /Catalog /Pages 2 0 R >>")
        val kids = (0 until nPages).joinToString(" ") { "${3 + it * 2} 0 R" }
        add("<< /Type /Pages /Kids [$kids] /Count $nPages >>")
        pages.forEachIndexed { i, content ->
            val pageObj = 3 + i * 2
            val contentObj = pageObj + 1
            add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_W $PAGE_H] /Contents $contentObj 0 R /Resources << /Font << /F1 $font1 0 R /F2 $font2 0 R >> >> >>")
            val bytes = content.toByteArray(StandardCharsets.ISO_8859_1)
            add("<< /Length ${bytes.size} >>\nstream\n$content\nendstream")
        }
        add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-BoldOblique >>")
        val header = "%PDF-1.4\n".toByteArray(StandardCharsets.ISO_8859_1)
        val body = ByteArray(header.size + objects.sumOf { it.size + 32 })
        var cursor = 0
        header.copyInto(body, cursor)
        cursor += header.size
        val offsets = IntArray(objects.size + 1)
        objects.forEachIndexed { i, bytes ->
            offsets[i + 1] = cursor
            val head = "${i + 1} 0 obj\n".toByteArray(StandardCharsets.ISO_8859_1)
            head.copyInto(body, cursor)
            cursor += head.size
            bytes.copyInto(body, cursor)
            cursor += bytes.size
            val tail = "\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1)
            tail.copyInto(body, cursor)
            cursor += tail.size
        }
        val xrefAt = cursor
        val xref = StringBuilder("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (i in 1..objects.size) xref.append(String.format(Locale.US, "%010d 00000 n \n", offsets[i]))
        val trailer = "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R /Info << /Title (Degree Audit Plan - ${escape(title)}) /Creator (Term Organize) >> >>\nstartxref\n$xrefAt\n%%EOF\n"
        val tail = (xref.toString() + trailer).toByteArray(StandardCharsets.ISO_8859_1)
        val out = ByteArray(cursor + tail.size)
        body.copyInto(out, 0, 0, cursor)
        tail.copyInto(out, cursor)
        return out
    }
}
