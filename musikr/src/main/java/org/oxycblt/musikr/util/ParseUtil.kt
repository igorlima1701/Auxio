/*
 * Copyright (c) 2024 Auxio Project
 * ParseUtil.kt is part of Auxio.
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
 
package org.oxycblt.musikr.util

/**
 * Split a [String] by the given selector, automatically handling escaped characters that satisfy
 * the selector.
 *
 * @param selector A block that determines if the string should be split at a given character.
 * @return One or more [String]s split by the selector.
 */
internal inline fun String.splitEscaped(selector: (Char) -> Boolean): List<String> {
    val split = mutableListOf<String>()
    var currentString = ""
    var i = 0

    while (i < length) {
        val a = get(i)
        val b = getOrNull(i + 1)

        if (selector(a)) {
            // Non-escaped separator, split the string here, making sure any stray whitespace
            // is removed.
            split.add(currentString)
            currentString = ""
            i++
            continue
        }

        if (b != null && a == '\\' && selector(b)) {
            // Is an escaped character, add the non-escaped variant and skip two
            // characters to move on to the next one.
            currentString += b
            i += 2
        } else {
            // Non-escaped, increment normally.
            currentString += a
            i++
        }
    }

    if (currentString.isNotEmpty()) {
        // Had an in-progress split string that is now terminated, add it.
        split.add(currentString)
    }

    return split
}

// TODO: Remove the escaping checks, it's too expensive to do this for every single tag.

/**
 * Fix trailing whitespace or blank contents in a [String].
 *
 * @return A string with trailing whitespace remove,d or null if the [String] was all whitespace or
 *   empty.
 */
internal fun String.correctWhitespace() = fixWindows1252().trim().ifBlank { null }

/**
 * Fork pessoal: muitos programas gravam tags "Latin-1" usando, na verdade, a tabela do Windows
 * (Windows-1252), onde por exemplo o byte 0x92 é o apóstrofo curvo (’). O TagLib lê esses bytes como
 * caracteres de controle invisíveis (U+0080 a U+009F), que aparecem como um quadradinho. Como esses
 * caracteres de controle nunca são usados de propósito em tags, eles são trocados pelos caracteres
 * equivalentes do Windows-1252, como fazem o Musicolet e o Mp3tag.
 */
fun String.fixWindows1252(): String {
    if (none { it in '\u0080'..'\u009F' }) return this
    val out = StringBuilder(length)
    for (c in this) {
        if (c in '\u0080'..'\u009F') {
            val mapped = WINDOWS_1252[c.code - 0x80]
            out.append(if (mapped != '\u0000') mapped else c)
        } else {
            out.append(c)
        }
    }
    return out.toString()
}

private val WINDOWS_1252 =
    charArrayOf(
        '\u20AC', '\u0000', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021',
        '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\u0000', '\u017D', '\u0000',
        '\u0000', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014',
        '\u02DC', '\u2122', '\u0161', '\u203A', '\u0153', '\u0000', '\u017E', '\u0178',
    )

/**
 * Fix trailing whitespace or blank contents within a list of [String]s.
 *
 * @return A list of non-blank strings with trailing whitespace removed.
 */
internal fun List<String>.correctWhitespace() = mapNotNull { it.correctWhitespace() }
