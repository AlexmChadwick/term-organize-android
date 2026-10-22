// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.core.model

/**
 * The terms a student can open from the home screen. Matches the Swift term menu:
 * the 10 newest Northeastern terms, then 6 CPS and 6 Law. Terms already saved on
 * disk stay in the list even when they fall outside that window.
 */
object TermMenu {
    fun listed(remote: List<Term>, localCodes: Collection<String> = emptyList()): List<Term> {
        val window = Term.College.entries.flatMap { college ->
            val limit = if (college == Term.College.NEU) 10 else 6
            remote.filter { it.college == college }.sortedByDescending { it.code }.take(limit)
        }
        val local = localCodes.map { code ->
            remote.firstOrNull { it.code == code } ?: Term(code, "Term $code")
        }
        return (window + local).distinctBy { it.code }.sortedByDescending { it.code }
    }

    /**
     * Fills in `Term <code>` placeholders from catalogs already on the device.
     * A description that came from Banner is left as Banner wrote it.
     */
    fun preferKnownNames(listed: List<Term>, known: Collection<Term>): List<Term> {
        if (known.isEmpty()) return listed
        val byCode = known.associateBy { it.code }
        return listed.map { term ->
            val loaded = byCode[term.code] ?: return@map term
            if (term.bannerDescription == "Term ${term.code}") loaded else term
        }
    }
}
