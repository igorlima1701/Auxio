/*
 * Copyright (c) 2026 Auxio Project
 * PreviewPlayer.kt is part of Auxio.
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
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Fork pessoal: prévia de uma música (arrastar a música para a esquerda numa lista).
 *
 * Toca [LENGTH_MS] a partir de mais ou menos um terço da música (nunca antes de 30 s). A música que
 * estava tocando pausa e volta sozinha quando a prévia acaba. Se você der play na música principal
 * durante a prévia, a prévia para.
 */
object PreviewPlayer : PlaybackStateManager.Listener {
    const val LENGTH_MS = 30_000L

    /** Música em prévia agora (null = nenhuma). */
    val current: StateFlow<Song?>
        field = MutableStateFlow<Song?>(null)

    private var context: Context? = null
    private var manager: PlaybackStateManager? = null
    private var player: ExoPlayer? = null
    private var resumeAfter = false
    private var pausingMain = false
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stop() }

    fun attach(context: Context, manager: PlaybackStateManager) {
        this.context = context.applicationContext
        this.manager = manager
        manager.addListener(this)
    }

    /** Começa (ou troca) a prévia para [song]. */
    fun start(song: Song) {
        val context = context ?: return
        val manager = manager
        if (current.value == null) {
            resumeAfter = manager?.progression?.isPlaying == true
            if (resumeAfter) {
                pausingMain = true
                manager?.playing(false)
                pausingMain = false
            }
        }
        val p =
            player
                ?: ExoPlayer.Builder(context).build().also { created ->
                    created.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        false,
                    )
                    created.addListener(
                        object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) stop()
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                L.w(error, "Prévia falhou")
                                stop()
                            }
                        }
                    )
                    player = created
                }
        val duration = song.durationMs
        var startMs = max(30_000L, duration / 3)
        if (startMs + LENGTH_MS > duration) startMs = max(0L, duration - LENGTH_MS)
        p.setMediaItem(MediaItem.fromUri(song.uri), startMs)
        p.prepare()
        p.play()
        current.value = song
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, LENGTH_MS)
    }

    /** Para a prévia e, se a música principal estava tocando antes, volta a tocá-la. */
    fun stop() = stop(resume = true)

    private fun stop(resume: Boolean) {
        handler.removeCallbacks(stopRunnable)
        player?.let {
            it.stop()
            it.release()
        }
        player = null
        if (current.value != null) {
            current.value = null
            if (resume && resumeAfter) manager?.playing(true)
        }
        resumeAfter = false
    }

    override fun onProgressionChanged(progression: Progression) {
        // Deu play na música principal durante a prévia: a prévia sai de cena.
        if (progression.isPlaying && current.value != null && !pausingMain) {
            stop(resume = false)
        }
    }
}
