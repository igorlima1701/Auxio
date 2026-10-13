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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
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
    /** Gravações por fazer: "campo|song uid" -> (música, campo, valor). */
    private val pending = ConcurrentHashMap<String, Triple<Song, TagField, String>>()
    private val collectionJobs = ConcurrentHashMap<String, Job>()

    private fun enqueue(song: Song, tagField: TagField, value: String) {
        pending["${tagField.key}|${song.uid}"] = Triple(song, tagField, value)
    }
    private var retryJob: Job? = null
    @Volatile private var resyncStarted = false
    private val importPrefs = context.getSharedPreferences("album_ratings_meta", Context.MODE_PRIVATE)

    fun attach() {
        AlbumRatings.onUserChange = { album, rating -> schedule(album, rating) }
        AlbumCollections.onUserChange = { albums -> albums.forEach(::scheduleCollections) }
    }

    /** Fork pessoal: coleções do álbum no campo ALBUMCOLLECTION (mesma gravação cautelosa). */
    private fun scheduleCollections(album: Album) {
        val key = album.uid.toString()
        collectionJobs.remove(key)?.cancel()
        collectionJobs[key] =
            scope.launch {
                delay(DEBOUNCE_MS)
                val value = AlbumCollections.tagValue(album)
                for (song in album.songs) enqueue(song, TagField.ALBUM_COLLECTION, value)
                flush()
            }
    }

    private fun schedule(album: Album, rating: Int?) {
        val key = album.uid.toString()
        albumJobs.remove(key)?.cancel()
        albumJobs[key] =
            scope.launch {
                delay(DEBOUNCE_MS)
                val value = rating?.toString() ?: ""
                for (song in album.songs) enqueue(song, TagField.ALBUM_RATING, value)
                flush()
            }
    }

    private val flushLock = kotlinx.coroutines.sync.Mutex()

    // Uma gravação por vez (a conferência da abertura e as mudanças de nota podem coincidir).
    private suspend fun flush() = flushLock.withLock { flushImpl() }

    private suspend fun flushImpl() {
        val playingUid = withContext(Dispatchers.Main) { playbackManager.currentSong?.uid }
        val written = mutableMapOf<TagField, Int>()
        val errors = mutableMapOf<TagField, MutableList<String>>()
        for ((key, entry) in pending.entries.toList()) {
            val (song, tagField, value) = entry
            if (song.uid == playingUid) continue
            val error = editor.write(song.uri, song.path.name ?: "", mapOf(tagField to value))
            if (error != null) {
                L.w("${tagField.key} não gravado em ${song.path.name}: $error")
                errors.getOrPut(tagField) { mutableListOf() } += error
            } else {
                written[tagField] = (written[tagField] ?: 0) + 1
            }
            pending.remove(key, entry)
        }
        // Fork pessoal: avisa na tela o resultado, para não falhar em silêncio.
        val texts = mutableListOf<String>()
        for (tagField in listOf(TagField.ALBUM_RATING, TagField.ALBUM_COLLECTION)) {
            val ok = written[tagField] ?: 0
            val failed = errors[tagField] ?: emptyList()
            val rating = tagField == TagField.ALBUM_RATING
            if (failed.isNotEmpty()) {
                texts +=
                    context.getString(
                        if (rating) R.string.lng_rating_tag_failed
                        else R.string.lng_collection_tag_failed,
                        failed.size,
                        failed.first(),
                    )
            } else if (ok > 0) {
                texts +=
                    context.getString(
                        if (rating) R.string.lng_rating_tag_saved
                        else R.string.lng_collection_tag_saved,
                        ok,
                    )
            }
        }
        if (texts.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                        context,
                        texts.joinToString("\n"),
                        android.widget.Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
        if (pending.isNotEmpty()) scheduleRetry()
    }

    /**
     * Fork pessoal: confere, uma vez por abertura do app, se os álbuns com nota têm a tag certa
     * (lê a primeira faixa de cada um) e regrava os que estiverem diferentes, como os que não
     * puderam ser gravados antes.
     */
    fun resyncIfNeeded(albums: List<Album>) {
        if (resyncStarted) return
        resyncStarted = true
        scope.launch {
            delay(RESYNC_DELAY_MS)
            for (album in albums) {
                val rating = AlbumRatings.get(album) ?: continue
                val song = album.songs.firstOrNull() ?: continue
                val tag = editor.info(song.uri)["ALBUMRATING"]?.trim().orEmpty()
                if (tag == rating.toString()) continue
                for (s in album.songs) enqueue(s, TagField.ALBUM_RATING, rating.toString())
            }
            // Fork pessoal: o mesmo para as coleções.
            for (album in AlbumCollections.withCollections(albums)) {
                val song = album.songs.firstOrNull() ?: continue
                val expected = AlbumCollections.tagValue(album)
                val tag = editor.info(song.uri)["ALBUMCOLLECTION"]?.trim().orEmpty()
                if (tag == expected) continue
                for (s in album.songs) enqueue(s, TagField.ALBUM_COLLECTION, expected)
            }
            if (pending.isNotEmpty()) flush()
        }
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
        importCollectionsIfNeeded(albums)
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

    /**
     * Fork pessoal: numa instalação nova (sem coleções no app), recupera as coleções lendo o campo
     * ALBUMCOLLECTION da primeira faixa de cada álbum. Roda uma vez só.
     */
    private fun importCollectionsIfNeeded(albums: List<Album>) {
        if (importPrefs.getBoolean(KEY_COLLECTIONS_IMPORTED, false)) return
        importPrefs.edit().putBoolean(KEY_COLLECTIONS_IMPORTED, true).apply()
        if (AlbumCollections.hasAny()) return
        scope.launch {
            var found = 0
            for (album in albums) {
                val song = album.songs.firstOrNull() ?: continue
                val tag = editor.info(song.uri)["ALBUMCOLLECTION"]?.trim().orEmpty()
                if (tag.isEmpty()) continue
                withContext(Dispatchers.Main) { AlbumCollections.restore(album, tag) }
                found++
            }
            L.d("Coleções recuperadas das tags: $found álbuns")
        }
    }

    private companion object {
        const val KEY_COLLECTIONS_IMPORTED = "collections_imported_from_tags"
        const val DEBOUNCE_MS = 4000L
        const val RETRY_MS = 15000L
        const val RESYNC_DELAY_MS = 8000L
        const val KEY_IMPORTED = "imported_from_tags"
    }
}
