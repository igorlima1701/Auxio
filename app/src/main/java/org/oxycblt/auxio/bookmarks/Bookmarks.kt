/*
 * Copyright (c) 2026 Auxio Project
 * Bookmarks.kt is part of Auxio.
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

package org.oxycblt.auxio.bookmarks

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.lyrics.LyricsFiles
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: marcadores/notas de uma música, num .txt com o mesmo nome dela, na mesma pasta (o
 * Syncthing leva para o PC). Uma linha por marcador: "[m:ss] nota". Linhas sem tempo no começo
 * continuam a nota de cima; as que vêm antes do primeiro marcador são uma nota geral.
 */
object Bookmarks {
    data class Mark(val timeMs: Long, val note: String)

    data class Sheet(
        val marks: List<Mark>,
        /** Texto antes do primeiro marcador (mantido como está). */
        val header: String,
        val fileName: String,
        val exists: Boolean,
        val needsAccess: Boolean,
    )

    const val EXT = "txt"

    /** Muda sempre que um arquivo de marcadores é gravado ou apagado. */
    val version: StateFlow<Int>
        field = MutableStateFlow(0)

    private fun bump() {
        version.value = version.value + 1
    }

    private val LINE =
        Regex("""^\s*\[?(\d{1,3}):(\d{2})(?::(\d{2}))?(?:[.,](\d{1,3}))?\]?\s*(?:[-–:]\s*)?(.*)$""")

    /** Lê os marcadores da música. Rodar fora da main. */
    fun load(context: Context, song: Song): Sheet {
        val file =
            try {
                LyricsFiles.readSibling(context, song, EXT)
            } catch (e: Exception) {
                LyricsFiles.Sibling(null, LyricsFiles.siblingName(song, EXT), true)
            }
        val text = file.text ?: return Sheet(emptyList(), "", file.fileName, false, file.needsAccess)
        val (marks, header) = parse(text)
        return Sheet(marks, header, file.fileName, true, file.needsAccess)
    }

    fun parse(text: String): Pair<List<Mark>, String> {
        val marks = mutableListOf<Mark>()
        val header = StringBuilder()
        var current: Pair<Long, StringBuilder>? = null
        for (raw in text.replace("\r\n", "\n").split('\n')) {
            val m = LINE.matchEntire(raw)
            if (m != null) {
                current?.let { marks += Mark(it.first, it.second.toString().trimEnd()) }
                val a = m.groupValues[1].toLong()
                val b = m.groupValues[2].toLong()
                val c = m.groupValues[3].toLongOrNull()
                val seconds = if (c != null) a * 3600 + b * 60 + c else a * 60 + b
                current = seconds * 1000 to StringBuilder(m.groupValues[5])
            } else if (current != null) {
                current.second.append('\n').append(raw)
            } else {
                header.append(raw).append('\n')
            }
        }
        current?.let { marks += Mark(it.first, it.second.toString().trimEnd()) }
        return marks.sortedBy { it.timeMs } to header.toString().trim('\n')
    }

    fun format(marks: List<Mark>, header: String): String {
        val out = StringBuilder()
        if (header.isNotBlank()) out.append(header.trimEnd()).append("\n\n")
        for (mark in marks.sortedBy { it.timeMs }) {
            out.append('[').append(time(mark.timeMs)).append("] ").append(mark.note.trimEnd()).append('\n')
        }
        return out.toString()
    }

    /** "m:ss" ou "h:mm:ss". */
    fun time(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Grava a lista (apaga o arquivo se ficar vazia e sem nota geral). Retorna o erro, ou null. */
    fun save(context: Context, song: Song, sheet: Sheet, marks: List<Mark>): String? {
        val error =
            if (marks.isEmpty() && sheet.header.isBlank()) {
                LyricsFiles.deleteSibling(context, song, EXT)
            } else {
                LyricsFiles.writeSibling(context, song, EXT, format(marks, sheet.header))
            }
        if (error == null) bump()
        return error
    }

    /** Salva uma cópia com outro nome, na mesma pasta. */
    fun saveAs(context: Context, song: Song, sheet: Sheet, name: String): String? {
        val clean = name.trim().let { if (it.endsWith(".txt", true)) it else "$it.txt" }
        return LyricsFiles.writeSibling(context, song, EXT, format(sheet.marks, sheet.header), clean)
    }

    fun delete(context: Context, song: Song): String? =
        LyricsFiles.deleteSibling(context, song, EXT).also { if (it == null) bump() }
}
