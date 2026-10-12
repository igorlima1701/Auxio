/*
 * Copyright (c) 2026 Auxio Project
 * AlbumRatingTagSync.kt is part of Auxio.
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

package org.oxycblt.auxio.rating

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.edit.TagEditor
import org.oxycblt.musikr.tag.edit.TagField
import timber.log.Timber as L

/**
 * Fork pessoal: espelha a nota do álbum no campo ALBUMRATING de todas as faixas.
 * - Dar, mudar ou tirar a nota agenda a gravação; ela acontece alguns segundos depois do último
 *   toque, para não regravar à toa.
 * - A música que está tocando fica para depois (gravar um arquivo aberto pelo player pode
 *   interromper a música); ela é gravada quando o player passar para outra.
 * - Numa instalação nova (sem notas salvas), as notas são recuperadas lendo o campo dos arquivos.
 *
 * A gravação usa o editor cauteloso de tags: confere áudio e demais tags antes de substituir o
 * arquivo. Se der errado numa faixa, o arquivo fica como estava.
 */
@Singleton
class AlbumRatingTagSync
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val playbackManager: PlaybackStateManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val editor = TagEditor(context)
    private val albumJobs = ConcurrentHashMap<String, Job>()
    /** Faixas ainda por gravar: song uid -> (música, valor). */
    private val pending = ConcurrentHashMap<String, Pair<Song, String>>()
    private var retryJob: Job? = null
    private val importPrefs = context.getSharedPreferences("album_ratings_meta", Context.MODE_PRIVATE)

    fun attach() {
        AlbumRatings.onUserChange = { album, rating -> schedule(album, rating) }
    }

    private fun schedule(album: Album, rating: Int?) {
        val key = album.uid.toString()
        albumJobs.remove(key)?.cancel()
        albumJobs[key] =
            scope.launch {
                delay(DEBOUNCE_MS)
                val value = rating?.toString() ?: ""
                for (song in album.songs) pending[song.uid.toString()] = song to value
                flush()
            }
    }

    private suspend fun flush() {
        val playingUid = withContext(Dispatchers.Main) { playbackManager.currentSong?.uid }
        for ((key, entry) in pending.entries.toList()) {
            val (song, value) = entry
            if (song.uid == playingUid) continue
            val error = editor.write(song.uri, song.path.name ?: "", mapOf(TagField.ALBUM_RATING to value))
            if (error != null) L.w("ALBUMRATING não gravado em ${song.path.name}: $error")
            pending.remove(key, entry)
        }
        if (pending.isNotEmpty()) scheduleRetry()
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob =
            scope.launch {
                delay(RETRY_MS)
                flush()
            }
    }

    /**
     * Numa instalação nova, lê o ALBUMRATING das faixas para recuperar as notas. Roda uma vez só, e
     * apenas se não houver nenhuma nota salva no app.
     */
    fun importIfNeeded(albums: List<Album>) {
        if (importPrefs.getBoolean(KEY_IMPORTED, false)) return
        if (AlbumRatings.hasAny()) {
            importPrefs.edit().putBoolean(KEY_IMPORTED, true).apply()
            return
        }
        importPrefs.edit().putBoolean(KEY_IMPORTED, true).apply()
        scope.launch {
            var found = 0
            for (album in albums) {
                val song = album.songs.firstOrNull() ?: continue
                val rating =
                    editor.info(song.uri)["ALBUMRATING"]?.trim()?.toIntOrNull() ?: continue
                if (rating in AlbumRatings.MIN..AlbumRatings.MAX) {
                    withContext(Dispatchers.Main) { AlbumRatings.restore(album, rating) }
                    found++
                }
            }
            L.d("Notas recuperadas das tags: $found")
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 4000L
        const val RETRY_MS = 15000L
        const val KEY_IMPORTED = "imported_from_tags"
    }
}
