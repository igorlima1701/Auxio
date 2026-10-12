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
                ratings[key] = value
            }
        }
    }

    fun get(album: Album): Int? = ratings[album.uid.toString()]

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
    }
}
