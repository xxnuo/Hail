package com.aistra.hail.utils

import net.sourceforge.pinyin4j.PinyinHelper

object AlphabetIndex {
    private val letters = 'A'..'Z'

    fun firstLetters(raw: CharSequence?): Set<Char> {
        if (raw.isNullOrBlank()) return emptySet()
        raw.forEach { char ->
            val result = when {
                char in 'A'..'Z' -> setOf(char)
                char in 'a'..'z' -> setOf(char.uppercaseChar())
                else -> PinyinHelper.toHanyuPinyinStringArray(char)?.mapNotNull {
                    it.firstOrNull()?.uppercaseChar()?.takeIf { letter -> letter in letters }
                }?.toSet()
            }
            if (!result.isNullOrEmpty()) return result
        }
        return emptySet()
    }
}
