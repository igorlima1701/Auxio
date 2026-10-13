/*
 * Copyright (c) 2026 Auxio Project
 * PlayTracker.kt is part of Auxio.
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
import kotlin.math.max
import kotlin.math.min
import org.oxycblt.auxio.playback.PlayHistory
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: conta as execuções pelo tempo realmente ouvido. Uma música conta uma vez quando o
 * tempo tocando (pausas e adiantamentos não entram) chega à metade da duração ou a 4 minutos, o que
 * vier primeiro. Tanto faz se depois ela vai até o fim ou é pulada. Repetir a música (ou voltar
 * ao começo dela depois de já contada) abre uma execução nova.
 */
class PlayTracker(private val player: Player) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var song: Song? = null
    private var accumulated = 0L
    private var segmentStart = -1L
    private var counted = false
    private val check = Runnable { evaluate() }

    init {
        song = player.currentMediaItem.song()
        if (player.isPlaying) startSegment()
    }

    private fun MediaItem?.song() = this?.localConfiguration?.tag as? Song

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) startSegment() else endSegment()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        endSegment()
        newInstance(mediaItem.song())
        if (player.isPlaying) startSegment()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // Voltou ao começo da mesma música depois de ela já ter contado: é outra execução.
        if (
            reason == Player.DISCONTINUITY_REASON_SEEK &&
                counted &&
                newPosition.mediaItemIndex == oldPosition.mediaItemIndex &&
                newPosition.positionMs < RESTART_MS
        ) {
            val playing = segmentStart >= 0
            endSegment()
            newInstance(song)
            if (playing) startSegment()
        }
    }

    private fun newInstance(next: Song?) {
        handler.removeCallbacks(check)
        song = next
        accumulated = 0L
        segmentStart = -1L
        counted = false
    }

    private fun startSegment() {
        if (segmentStart >= 0) return
        if (song == null) song = player.currentMediaItem.song()
        segmentStart = SystemClock.elapsedRealtime()
        schedule()
    }

    private fun endSegment() {
        if (segmentStart < 0) return
        accumulated += SystemClock.elapsedRealtime() - segmentStart
        segmentStart = -1L
        handler.removeCallbacks(check)
        evaluate()
    }

    private fun listened() =
        accumulated + if (segmentStart >= 0) SystemClock.elapsedRealtime() - segmentStart else 0L

    private fun threshold(): Long {
        val duration = song?.durationMs?.takeIf { it > 0 } ?: player.duration.takeIf { it > 0 }
        return if (duration == null) MAX_THRESHOLD_MS else min(duration / 2, MAX_THRESHOLD_MS)
    }

    private fun schedule() {
        handler.removeCallbacks(check)
        if (counted) return
        handler.postDelayed(check, max(threshold() - listened(), 500L))
    }

    private fun evaluate() {
        val current = song ?: return
        if (counted) return
        val listened = listened()
        if (listened >= threshold()) {
            counted = true
            PlayHistory.record(current, listened)
        } else if (segmentStart >= 0) {
            schedule()
        }
    }

    fun release() {
        handler.removeCallbacks(check)
        player.removeListener(this)
    }

    private companion object {
        const val MAX_THRESHOLD_MS = 4 * 60 * 1000L
        const val RESTART_MS = 3000L
    }
}
