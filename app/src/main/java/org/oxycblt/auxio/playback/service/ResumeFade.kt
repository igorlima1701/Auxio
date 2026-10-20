/*
 * Copyright (c) 2026 Auxio Project
 * ResumeFade.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Fork pessoal: ao voltar da pausa (mesma música, do mesmo ponto), o volume sobe aos poucos, de
 * zero até o normal, em cerca de 1 segundo. Pausar continua cortando na hora. Não usa animações do
 * sistema (que podem estar desligadas no aparelho).
 */
class ResumeFade(private val player: Player) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var pausedItem: MediaItem? = null
    private var fadeStart = 0L

    private val step =
        object : Runnable {
            override fun run() {
                val t = (SystemClock.uptimeMillis() - fadeStart).toFloat() / DURATION_MS
                if (t >= 1f) {
                    player.volume = 1f
                    return
                }
                // Curva suave (o ouvido percebe volume de forma não linear).
                player.volume = t * t
                handler.postDelayed(this, STEP_MS)
            }
        }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) {
            stop()
            pausedItem = player.currentMediaItem
            return
        }
        val item = player.currentMediaItem
        val resuming = item != null && item == pausedItem && player.currentPosition > 0
        pausedItem = null
        if (resuming) start()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Trocou de música (pulou, outra fila…): não é "voltar da pausa".
        if (!player.playWhenReady) pausedItem = null
    }

    private fun start() {
        handler.removeCallbacks(step)
        fadeStart = SystemClock.uptimeMillis()
        player.volume = 0f
        handler.post(step)
    }

    private fun stop() {
        handler.removeCallbacks(step)
        player.volume = 1f
    }

    fun release() {
        stop()
        player.removeListener(this)
    }

    private companion object {
        const val DURATION_MS = 1000f
        const val STEP_MS = 25L
    }
}
