/*
 * Copyright (c) 2026 Auxio Project
 * AlbumFilter.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home.filter

import org.oxycblt.auxio.rating.AlbumRatings
import org.oxycblt.musikr.Album

/**
 * Fork pessoal: filtro combinável da aba Álbuns.
 *
 * Dentro de cada critério vale "ou" (ex.: nota 4 ou 5); entre critérios vale "e" (ex.: nota 4 ou 5
 * e ano 2002). Um critério vazio não filtra nada.
 *
 * @param years Anos aceitos.
 * @param ratings Notas aceitas; [NO_RATING] representa "sem nota".
 */
data class AlbumFilter(val years: Set<Int> = emptySet(), val ratings: Set<Int> = emptySet()) {
    val isEmpty: Boolean
        get() = years.isEmpty() && ratings.isEmpty()

    fun matches(album: Album): Boolean {
        if (years.isNotEmpty()) {
            val dates = album.dates ?: return false
            if (years.none { it in dates.min.year..dates.max.year }) return false
        }
        if (ratings.isNotEmpty()) {
            val rating = AlbumRatings.get(album) ?: NO_RATING
            if (rating !in ratings) return false
        }
        return true
    }

    companion object {
        const val NO_RATING = -1
    }
}
