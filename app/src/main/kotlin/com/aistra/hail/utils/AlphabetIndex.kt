package com.aistra.hail.utils

import net.sourceforge.pinyin4j.PinyinHelper
import java.util.Locale

object AlphabetIndex {
    private val letters = 'A'..'Z'

    fun primaryLetter(raw: CharSequence?): Char? = firstLetters(raw).minOrNull()

    fun firstLetters(raw: CharSequence?): Set<Char> {
        if (raw.isNullOrBlank()) return emptySet()
        raw.forEach { char ->
            val result = indexLetters(char)
            if (!result.isNullOrEmpty()) return result
        }
        return emptySet()
    }

    fun startsWithLetter(raw: CharSequence?, letter: Char): Boolean =
        primaryLetter(raw) == letter.uppercaseChar()

    fun sortKey(raw: CharSequence?): String {
        if (raw.isNullOrBlank()) return "{"
        val primary = primaryLetter(raw) ?: '{'
        val normalized = buildString {
            var started = false
            raw.forEach { char ->
                val hasIndexLetter = !indexLetters(char).isNullOrEmpty()
                if (!started && !hasIndexLetter) return@forEach
                started = true
                append(sortToken(char))
            }
        }.ifEmpty { raw.toString().lowercase(Locale.ROOT) }
        return "$primary\u0000$normalized"
    }

    private fun indexLetters(char: Char): Set<Char>? = when {
        char in 'A'..'Z' -> setOf(char)
        char in 'a'..'z' -> setOf(char.uppercaseChar())
        else -> pinyin(char)?.mapNotNull {
            it.firstOrNull()?.uppercaseChar()?.takeIf { letter -> letter in letters }
        }?.toSet()
    }

    private fun sortToken(char: Char): String = when {
        char in 'A'..'Z' || char in 'a'..'z' -> char.lowercaseChar().toString()
        else -> pinyin(char)?.minOrNull() ?: char.toString().lowercase(Locale.ROOT)
    }

    private fun pinyin(char: Char): List<String>? = PinyinHelper.toHanyuPinyinStringArray(char)?.map {
        it.dropLastWhile { item -> item.isDigit() }
    }?.filter { it.isNotEmpty() }
}
