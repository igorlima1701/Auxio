/*
 * Copyright (c) 2026 Auxio Project
 * Lyrics.kt is part of Auxio.
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

package org.oxycblt.auxio.lyrics

/** Fork pessoal: uma linha da letra. [timeMs] nulo = letra sem tempo marcado. */
data class LyricLine(val timeMs: Long?, val text: String)

/** Fork pessoal: letra pronta para exibir. [synced] = tem tempos marcados ([mm:ss.xx]). */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {
    /** Índice da linha que está tocando em [positionMs], ou -1 antes da primeira. */
    fun indexAt(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if ((lines[mid].timeMs ?: 0) <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        private val TIME = Regex("""^\s*\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
        private val META = Regex("""^\s*\[([a-zA-Z#]+):(.*)]\s*$""")

        /**
         * Lê o texto de um arquivo .lrc (ou de uma letra embutida). Aceita várias marcações na
         * mesma linha ([00:12.00][00:45.00]texto), a marcação [offset:] e as marcações de palavra
         * (<00:12.50>) do formato estendido, que são descartadas.
         */
        fun parse(text: String): Lyrics {
            var offset = 0L
            val timed = mutableListOf<LyricLine>()
            val plain = mutableListOf<String>()
            for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                var rest = raw
                val times = mutableListOf<Long>()
                while (true) {
                    val m = TIME.find(rest) ?: break
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val fracMs =
                        when (frac.length) {
                            0 -> 0L
                            1 -> frac.toLong() * 100
                            2 -> frac.toLong() * 10
                            else -> frac.toLong()
                        }
                    times += (min * 60 + sec) * 1000 + fracMs
                    rest = rest.substring(m.range.last + 1)
                }
                if (times.isEmpty()) {
                    val meta = META.find(rest)
                    if (meta != null) {
                        if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                            offset = meta.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0L
                        }
                        continue
                    }
                    plain += rest.trimEnd()
                } else {
                    val line = rest.replace(WORD_TIME, "").trim()
                    for (t in times) timed += LyricLine(t, line)
                }
            }
            if (timed.isNotEmpty()) {
                // Pelo padrão LRC, offset positivo adianta a letra.
                val lines =
                    timed
                        .map { it.copy(timeMs = ((it.timeMs ?: 0) - offset).coerceAtLeast(0)) }
                        .sortedBy { it.timeMs }
                return Lyrics(lines, true)
            }
            // Sem tempos: tira as linhas em branco do começo e do fim.
            val trimmed = plain.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            return Lyrics(trimmed.map { LyricLine(null, it) }, false)
        }

        /** Formata [ms] como marcação LRC: [mm:ss.xx]. */
        fun stamp(ms: Long): String {
            val total = ms.coerceAtLeast(0)
            val min = total / 60000
            val sec = (total / 1000) % 60
            val cs = (total % 1000) / 10
            return String.format(java.util.Locale.US, "[%02d:%02d.%02d]", min, sec, cs)
        }
    }
}
