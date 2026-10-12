/*
 * Copyright (c) 2026 Auxio Project
 * AlbumRatings.kt is part of Auxio.
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
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.musikr.Album

/**
 * Fork pessoal: notas de álbuns (0 a 5), guardadas dentro do app. Sem nota = null.
 *
 * As notas ficam todas em memória (leitura instantânea) e são gravadas num arquivo de preferências
 * próprio, separado das configurações do app. A chave é o identificador do álbum no Auxio.
 */
object AlbumRatings {
    const val MIN = 0
    const val MAX = 5
    private const val PENDING_PREFIX = "pending:"

    private lateinit var prefs: SharedPreferences
    private val ratings = ConcurrentHashMap<String, Int>()

    /** Muda sempre que alguma nota muda, para listas reordenarem/refiltrarem. */
    val version: StateFlow<Long>
        field = MutableStateFlow(0L)

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("album_ratings", Context.MODE_PRIVATE)
        for ((key, value) in prefs.all) {
            if (value is Int && value in MIN..MAX) {
                if (key.startsWith(PENDING_PREFIX)) pending[key] = value else ratings[key] = value
            }
        }
    }

    /**
     * Notas à espera de um álbum novo: quando o nome ou o artista de um álbum muda (edição de tags),
     * o identificador do álbum muda junto. A nota fica guardada pelo arquivo de uma das faixas e é
     * passada para o álbum novo na próxima varredura.
     */
    private val pending = ConcurrentHashMap<String, Int>()

    fun get(album: Album): Int? = ratings[album.uid.toString()]

    /** Guarda a nota de [album] para ser reaplicada depois de uma edição de tags. */
    fun rememberForRetag(album: Album) {
        val rating = get(album) ?: return
        val song = album.songs.firstOrNull() ?: return
        val key = PENDING_PREFIX + song.uri
        pending[key] = rating
        prefs.edit().putInt(key, rating).apply()
    }

    /** Reaplica notas pendentes aos álbuns novos. Chamado sempre que a lista de álbuns muda. */
    fun resolvePending(albums: List<Album>) {
        if (pending.isEmpty()) return
        val editor = prefs.edit()
        var changed = false
        for (album in albums) {
            for (song in album.songs) {
                val key = PENDING_PREFIX + song.uri
                val rating = pending.remove(key) ?: continue
                editor.remove(key)
                val albumKey = album.uid.toString()
                if (!ratings.containsKey(albumKey)) {
                    ratings[albumKey] = rating
                    editor.putInt(albumKey, rating)
                    changed = true
                }
            }
        }
        editor.apply()
        if (changed) version.value = version.value + 1
    }

    /** Fork pessoal: avisado quando o usuário muda uma nota (para gravar na tag ALBUMRATING). */
    @Volatile var onUserChange: ((Album, Int?) -> Unit)? = null

    fun hasAny(): Boolean = ratings.isNotEmpty()

    /** Recupera uma nota lida das tags (sem regravar os arquivos). */
    fun restore(album: Album, rating: Int) {
        val key = album.uid.toString()
        if (ratings.containsKey(key)) return
        ratings[key] = rating
        prefs.edit().putInt(key, rating).apply()
        version.value = version.value + 1
    }

    fun set(album: Album, rating: Int?) {
        val key = album.uid.toString()
        if (rating == null) {
            if (ratings.remove(key) == null) return
            prefs.edit().remove(key).apply()
        } else {
            val clamped = rating.coerceIn(MIN, MAX)
            if (ratings.put(key, clamped) == clamped) return
            prefs.edit().putInt(key, clamped).apply()
        }
        version.value = version.value + 1
        onUserChange?.invoke(album, rating?.coerceIn(MIN, MAX))
    }
}
