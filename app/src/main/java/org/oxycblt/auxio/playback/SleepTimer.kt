/*
 * Copyright (c) 2026 Auxio Project
 * SleepTimer.kt is part of Auxio.
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

package org.oxycblt.auxio.playback

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork pessoal: timer para parar a reprodução, depois de um tempo ou de N músicas. ("Depois de uma
 * música específica" usa o [StopAfterSong].) Tudo roda na thread principal.
 */
object SleepTimer {
    sealed interface Mode {
        /** Pausa quando o relógio ([android.os.SystemClock.elapsedRealtime]) chegar em [atMs]. */
        data class Time(val atMs: Long, val totalMs: Long) : Mode

        /** Pausa no fim da música quando faltar 1; [left] conta a música atual. */
        data class Songs(val left: Int) : Mode
    }

    val mode: StateFlow<Mode?>
        field = MutableStateFlow<Mode?>(null)

    /** Pausa o player (montado pelo serviço). */
    @Volatile var pause: (() -> Unit)? = null

    /** Avisado quando muda, para o player recalcular a pausa no fim da música. */
    @Volatile var listener: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable {
        mode.value = null
        pause?.invoke()
        listener?.invoke()
    }

    fun startTime(durationMs: Long) {
        if (durationMs <= 0) return
        handler.removeCallbacks(fire)
        mode.value = Mode.Time(android.os.SystemClock.elapsedRealtime() + durationMs, durationMs)
        handler.postDelayed(fire, durationMs)
        listener?.invoke()
    }

    fun startSongs(count: Int) {
        if (count <= 0) return
        handler.removeCallbacks(fire)
        mode.value = Mode.Songs(count)
        listener?.invoke()
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        mode.value = null
        listener?.invoke()
    }

    /** Pausar no fim da música atual? */
    val stopsAtEndOfSong: Boolean
        get() = (mode.value as? Mode.Songs)?.left == 1

    /** Uma música terminou e outra começou sozinha (inclusive repetindo a mesma). */
    fun onSongFinished() {
        val songs = mode.value as? Mode.Songs ?: return
        mode.value = if (songs.left <= 1) null else Mode.Songs(songs.left - 1)
        listener?.invoke()
    }

    /** O player pausou no fim da música: se era a última da contagem, o timer acabou. */
    fun onPausedAtEnd() {
        if (stopsAtEndOfSong) {
            mode.value = null
            listener?.invoke()
        }
    }
}
