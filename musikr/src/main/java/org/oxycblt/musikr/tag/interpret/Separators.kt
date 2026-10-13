/*
 * Copyright (c) 2024 Auxio Project
 * Separators.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.musikr.tag.interpret

import org.oxycblt.musikr.util.correctWhitespace
import org.oxycblt.musikr.util.splitEscaped

/**
 * Defines the user-specified parsing of multi-value tags. This should be used to parse any tags
 * that may be delimited with a separator character.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
interface Separators {
    /**
     * Parse a separated value from one or more strings. If the value is already composed of more
     * than one value, nothing is done. Otherwise, it will attempt to split it based on the user's
     * separator preferences.
     *
     * @return A new list of one or more [String]s parsed by the separator configuration
     */
    fun split(strings: List<String>): List<String>

    companion object {
        const val COMMA = ','
        const val SEMICOLON = ';'
        const val SLASH = '/'
        const val PLUS = '+'
        const val AND = '&'

        /**
         * Creates a new instance from a string of separator characters to use.
         *
         * @param chars The separator characters to use. Each character in the string will be
         *   checked for when splitting a string list.
         * @return A new [Separators] instance reflecting the separators.
         */
        fun from(chars: String) = from(chars, emptyList())

        /**
         * Fork pessoal: como [from], mas os nomes em [exceptions] (ex.: "Tyler, The Creator") nunca
         * são separados, sem precisar da barra invertida na tag.
         */
        fun from(chars: String, exceptions: List<String>) =
            if (chars.isNotEmpty()) {
                CharSeparators(chars.toSet(), exceptions.map { it.trim() }.filter { it.isNotEmpty() })
            } else {
                NoSeparators
            }
    }
}

private data class CharSeparators(
    private val chars: Set<Char>,
    private val exceptions: List<String> = emptyList(),
) : Separators {
    // Só as exceções que têm algum separador ativo; as maiores primeiro.
    private val guarded =
        exceptions.filter { e -> e.any { chars.contains(it) } }.sortedByDescending { it.length }

    override fun split(strings: List<String>) =
        if (strings.size == 1) splitImpl(strings.first()) else strings

    private fun splitImpl(string: String) =
        protect(string).splitEscaped { chars.contains(it) }.correctWhitespace()

    /** Escapa os separadores dentro dos nomes da lista de exceções. */
    private fun protect(string: String): String {
        if (guarded.isEmpty() || string.none { chars.contains(it) }) return string
        var out = string
        for (name in guarded) {
            var from = 0
            while (true) {
                val idx = out.indexOf(name, from, ignoreCase = true)
                if (idx < 0) break
                val escaped =
                    buildString {
                        for (c in out.substring(idx, idx + name.length)) {
                            if (chars.contains(c)) append('\\')
                            append(c)
                        }
                    }
                out = out.substring(0, idx) + escaped + out.substring(idx + name.length)
                from = idx + escaped.length
            }
        }
        return out
    }
}

private object NoSeparators : Separators {
    override fun split(strings: List<String>) = strings
}
