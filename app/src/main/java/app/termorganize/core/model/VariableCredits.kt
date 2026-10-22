// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.core.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Variable credit is a high value different from the low value.
 * Banner's creditHours is the starting choice when it sits inside that range.
 */
object VariableCredits {
    fun isVariable(low: Double?, high: Double?): Boolean {
        if (low == null || high == null) return false
        return abs(high - low) > 1e-6
    }

    fun defaultChosen(creditHours: Double?, low: Double, high: Double): Double {
        val hours = creditHours
        val chosen = if (hours != null && hours >= low - 1e-6 && hours <= high + 1e-6) hours else low
        return clamp(chosen, low, high)
    }

    /** Whole numbers only, kept inside low through high. */
    fun clamp(value: Double, low: Double, high: Double): Double {
        val least = ceil(low - 1e-9).toInt()
        val most = floor(high + 1e-9).toInt()
        if (most < least) return low
        return value.roundToInt().coerceIn(least, most).toDouble()
    }

    fun label(chosen: Double, low: Double, high: Double): String =
        "Credits: ${CreditFormatter.number(chosen)} (${CreditFormatter.number(low)} to ${CreditFormatter.number(high)})"

    /** Credits a schedule row contributes. A variable row uses its own choice. */
    fun accounted(credits: Double?, creditLow: Double?, creditHigh: Double?, course: Course): Double {
        val low = creditLow ?: course.minCredits
        val high = creditHigh ?: course.maxCredits
        if (!isVariable(low, high)) return course.minCredits
        val chosen = credits ?: return defaultChosen(course.creditHours, low, high)
        return clamp(chosen, low, high)
    }

    data class Seed(val credits: Double, val low: Double?, val high: Double?)

    /** Starting choice for a new row. Fixed courses keep the value the caller already has. */
    fun seed(course: Course?, entered: Double): Seed {
        if (course == null || !course.isVariableCredit) return Seed(entered, null, null)
        return Seed(
            defaultChosen(course.creditHours, course.minCredits, course.maxCredits),
            course.minCredits,
            course.maxCredits,
        )
    }
}
