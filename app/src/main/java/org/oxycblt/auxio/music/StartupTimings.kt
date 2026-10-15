/*
 * Copyright (c) 2026 Auxio Project
 * StartupTimings.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import org.oxycblt.musikr.MusikrTimings

/**
 * Fork pessoal: tempos da última abertura do app (do início do processo até a grade de álbuns de
 * verdade aparecer), mostrados nas Configurações para achar o que ainda é lento.
 */
object StartupTimings {
    private var prefs: SharedPreferences? = null
    @Volatile private var quickStartAt = -1L
    @Volatile private var quickLine: String? = null
    @Volatile private var emitMs = -1L
    @Volatile private var albumsShown = false

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("startup_timings", Context.MODE_PRIVATE)
        }
    }

    private fun sinceStart() = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    fun markQuickStart() {
        quickStartAt = sinceStart()
    }

    /** Leitura rápida terminou (dados da etapa vêm do musikr). */
    fun markQuickDone() {
        quickLine =
            "leitura rápida: ${MusikrTimings.totalMs} ms (${MusikrTimings.songs} músicas; " +
                "arquivos+cache ${MusikrTimings.exploreMs} ms, tags ${MusikrTimings.extractMs} ms)"
    }

    fun markEmit(ms: Long) {
        if (emitMs < 0) emitMs = ms
    }

    /** A grade de álbuns de verdade recebeu a lista. Grava o resumo uma vez por abertura. */
    fun markAlbumsShown() {
        if (albumsShown) return
        albumsShown = true
        val text = buildString {
            append("início da leitura: ").append(quickStartAt).append(" ms após abrir\n")
            quickLine?.let { append(it).append('\n') }
            if (emitMs >= 0) append("entrega às telas: ").append(emitMs).append(" ms\n")
            append("grade de álbuns pronta: ").append(sinceStart()).append(" ms após abrir")
        }
        prefs?.edit()?.putString(KEY, text)?.apply()
    }

    fun last(context: Context): String? {
        init(context)
        return prefs?.getString(KEY, null)
    }

    private const val KEY = "last"
}
