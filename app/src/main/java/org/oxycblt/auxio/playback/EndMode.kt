/*
 * Copyright (c) 2026 Auxio Project
 * EndMode.kt is part of Auxio.
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

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.playback.state.RepeatMode

/**
 * Fork pessoal: o que fazer quando uma música acaba e quando a fila acaba (como no Music OLED).
 *
 * O [RepeatMode] do Auxio continua existindo (notificação, widget) e é derivado daqui:
 * repetir a música = TRACK; repetir a fila = ALL; o resto = NONE.
 */
object EndMode {
    enum class SongEnd {
        /** Para ali (pausa no fim da música). */
        STOP,
        /** Carrega a próxima e pausa. */
        LOAD_NEXT,
        /** Toca a próxima. */
        PLAY_NEXT,
        /** Repete a mesma música. */
        REPEAT,
    }

    enum class QueueEnd {
        STOP,
        NEXT_QUEUE,
        REPEAT,
    }

    data class State(
        val advanced: Boolean = false,
        val songEnd: SongEnd = SongEnd.PLAY_NEXT,
        val toFirst: Boolean = true,
        val reshuffle: Boolean = true,
        val queueEnd: QueueEnd = QueueEnd.STOP,
    ) {
        val repeatMode: RepeatMode
            get() =
                when {
                    songEnd == SongEnd.REPEAT -> RepeatMode.TRACK
                    queueEnd == QueueEnd.REPEAT -> RepeatMode.ALL
                    else -> RepeatMode.NONE
                }

        /** As três opções do modo simples, a partir do estado. */
        val simple: RepeatMode
            get() = repeatMode

        /** Pausa no fim de cada música (parar ali ou carregar a próxima e pausar). */
        val pausesAtEnd: Boolean
            get() = songEnd == SongEnd.STOP || songEnd == SongEnd.LOAD_NEXT
    }

    val DEFAULT = State()

    val state: StateFlow<State>
        field = MutableStateFlow(DEFAULT)

    /** Avisado (thread principal) quando muda, para o player se ajustar. */
    @Volatile var listener: (() -> Unit)? = null

    /** Pula para a próxima fila guardada (montado pelas filas múltiplas). Retorna false se não há. */
    @Volatile var nextQueue: ((play: Boolean) -> Boolean)? = null

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("end_mode", Context.MODE_PRIVATE)
        prefs = p
        state.value =
            State(
                advanced = p.getBoolean("advanced", false),
                songEnd = enumOr(p.getString("song", null), SongEnd.PLAY_NEXT),
                toFirst = p.getBoolean("first", true),
                reshuffle = p.getBoolean("reshuffle", true),
                queueEnd = enumOr(p.getString("queue", null), QueueEnd.STOP),
            )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    fun set(new: State) {
        if (new == state.value) return
        state.value = new
        prefs
            ?.edit()
            ?.putBoolean("advanced", new.advanced)
            ?.putString("song", new.songEnd.name)
            ?.putBoolean("first", new.toFirst)
            ?.putBoolean("reshuffle", new.reshuffle)
            ?.putString("queue", new.queueEnd.name)
            ?.apply()
        listener?.invoke()
    }

    /** Escolha do modo simples (ou vinda da notificação/widget). */
    fun simpleState(base: State, mode: RepeatMode): State =
        when (mode) {
            RepeatMode.NONE -> base.copy(songEnd = SongEnd.PLAY_NEXT, queueEnd = QueueEnd.STOP)
            RepeatMode.ALL -> base.copy(songEnd = SongEnd.PLAY_NEXT, queueEnd = QueueEnd.REPEAT)
            RepeatMode.TRACK -> base.copy(songEnd = SongEnd.REPEAT)
        }

    /** O Auxio pediu [mode] (notificação, widget, fila restaurada): só muda se for diferente. */
    fun applyRepeatMode(mode: RepeatMode) {
        val current = state.value
        if (current.repeatMode == mode) return
        set(simpleState(current, mode))
    }
}
