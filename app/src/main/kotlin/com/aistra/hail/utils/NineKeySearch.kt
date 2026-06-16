package com.aistra.hail.utils

object NineKeySearch {
    fun search(query: String?, vararg strings: String?): Boolean =
        query.isNullOrEmpty() || strings.any {
            !it.isNullOrEmpty() && getNineKeyIndexes(it).any { index ->
                FuzzySearch.search(index, query)
            }
        }

    private fun getNineKeyIndexes(raw: String): List<String> = buildList {
        add(toNineKey(raw))
        PinyinSearch.getPinyinInitials(raw).mapTo(this) { toNineKey(it) }
        PinyinSearch.getPinyinFullSpell(raw).mapTo(this) { toNineKey(it) }
    }
        .filter { it.isNotEmpty() }
        .distinct()

    private fun toNineKey(raw: String): String = buildString {
        for (ch in raw) {
            toNineKey(ch)?.let(::append)
        }
    }

    private fun toNineKey(raw: Char): Char? = raw.lowercaseChar().let {
        when (it) {
            in '0'..'9' -> it
            in 'a'..'c' -> '2'
            in 'd'..'f' -> '3'
            in 'g'..'i' -> '4'
            in 'j'..'l' -> '5'
            in 'm'..'o' -> '6'
            in 'p'..'s' -> '7'
            in 't'..'v' -> '8'
            in 'w'..'z' -> '9'
            else -> null
        }
    }
}
