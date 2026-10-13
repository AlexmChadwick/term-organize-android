// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize.core.model

class CourseEquivalences private constructor(
    val groups: List<List<CourseKey>>,
    private val groupIndex: Map<CourseKey, Int>,
) {
    val isEmpty: Boolean get() = groups.isEmpty()
    val courses: Set<CourseKey> get() = groupIndex.keys

    fun equivalents(of: CourseKey): Set<CourseKey> {
        val i = groupIndex[of] ?: return emptySet()
        return groups[i].filter { it != of }.toSet()
    }

    fun group(of: CourseKey): List<CourseKey> = groupIndex[of]?.let { groups[it] } ?: listOf(of)

    fun areEquivalent(a: CourseKey, b: CourseKey): Boolean {
        if (a == b) return true
        val i = groupIndex[a] ?: return false
        val j = groupIndex[b] ?: return false
        return i == j
    }

    fun expanding(taken: Set<CourseKey>): Set<CourseKey> {
        if (isEmpty) return taken
        val result = taken.toMutableSet()
        val seen = HashSet<Int>()
        for (key in taken) {
            val i = groupIndex[key] ?: continue
            if (!seen.add(i)) continue
            result.addAll(groups[i])
        }
        return result
    }

    fun satisfiers(of: CourseKey, taken: Set<CourseKey>): List<CourseKey> {
        val result = mutableListOf<CourseKey>()
        if (of in taken) result.add(of)
        val i = groupIndex[of] ?: return result
        result += groups[i].filter { it != of && it in taken }
        return result
    }

    fun satisfier(of: CourseKey, taken: Set<CourseKey>): CourseKey? {
        if (of in taken) return of
        val i = groupIndex[of] ?: return null
        return groups[i].firstOrNull { it in taken }
    }

    fun merging(other: CourseEquivalences): CourseEquivalences {
        if (other.isEmpty) return this
        if (isEmpty) return other
        return fromGroups(groups + other.groups)
    }

    fun adding(a: CourseKey, b: CourseKey) = merging(fromGroups(listOf(listOf(a, b))))

    companion object {
        val none: CourseEquivalences = CourseEquivalences(emptyList(), emptyMap())

        operator fun invoke(groups: List<List<CourseKey>> = emptyList()): CourseEquivalences = fromGroups(groups)

        fun fromPairs(pairs: List<Pair<CourseKey, CourseKey>>): CourseEquivalences =
            fromGroups(pairs.map { listOf(it.first, it.second) })

        fun fromGroups(input: List<List<CourseKey>>): CourseEquivalences {
            val parent = HashMap<CourseKey, CourseKey>()
            fun add(key: CourseKey) {
                if (key !in parent) parent[key] = key
            }
            fun find(key: CourseKey): CourseKey {
                add(key)
                var root = key
                while (parent[root] != root) root = parent.getValue(root)
                var node = key
                while (parent[node] != root) {
                    val next = parent.getValue(node)
                    parent[node] = root
                    node = next
                }
                return root
            }
            fun union(a: CourseKey, b: CourseKey) {
                val ra = find(a)
                val rb = find(b)
                if (ra == rb) return
                if (ra < rb) parent[rb] = ra else parent[ra] = rb
            }
            for (group in input) {
                val first = group.firstOrNull() ?: continue
                add(first)
                for (other in group.drop(1)) union(first, other)
            }
            val buckets = HashMap<CourseKey, MutableList<CourseKey>>()
            for (key in parent.keys) buckets.getOrPut(find(key)) { mutableListOf() }.add(key)
            val built = buckets.values.filter { it.size > 1 }.map { it.sorted() }.sortedBy { it.first() }
            val index = HashMap<CourseKey, Int>()
            built.forEachIndexed { i, group -> group.forEach { index[it] = i } }
            return CourseEquivalences(built, index)
        }
    }
}
