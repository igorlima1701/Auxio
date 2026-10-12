/*
 * Copyright (c) 2026 Auxio Project
 * StopAfterSong.kt is part of Auxio.
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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.musikr.Music

/**
 * Fork pessoal: "Parar depois desta música". Guarda qual música deve ser a última; quando ela
 * termina, o player pausa (sem passar para a próxima). Vale uma vez e depois se desfaz.
 */
object StopAfterSong {
    val target: StateFlow<Music.UID?>
        field = MutableStateFlow<Music.UID?>(null)

    /** Avisado (na thread principal) quando o alvo muda, para o player se ajustar. */
    @Volatile var listener: (() -> Unit)? = null

    fun set(uid: Music.UID?) {
        target.value = uid
        listener?.invoke()
    }

    /** Liga/desliga: tocar de novo na mesma música cancela. */
    fun toggle(uid: Music.UID) = set(if (target.value == uid) null else uid)
}
