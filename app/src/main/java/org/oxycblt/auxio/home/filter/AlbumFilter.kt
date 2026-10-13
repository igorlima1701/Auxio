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

import android.content.Context
import org.oxycblt.auxio.R
import org.oxycblt.auxio.rating.AlbumCollections
import org.oxycblt.auxio.rating.AlbumRatings
import org.oxycblt.musikr.Album

/**
 * Fork pessoal: filtro combinável da aba Álbuns.
 *
 * Cada opção pode estar incluída (mostra só esses) ou excluída (esconde esses). Dentro de cada
 * critério, as incluídas valem "ou" (ex.: nota 4 ou 5); entre critérios vale "e" (ex.: nota 4 ou 5
 * e ano 2002). As excluídas sempre tiram do resultado. Um critério vazio não filtra nada.
 *
 * @param years Anos aceitos.
 * @param ratings Notas aceitas; [NO_RATING] representa "sem nota".
 * @param collections Coleções aceitas.
 */
data class AlbumFilter(
    val years: Set<Int> = emptySet(),
    val ratings: Set<Int> = emptySet(),
    val collections: Set<String> = emptySet(),
    val notYears: Set<Int> = emptySet(),
    val notRatings: Set<Int> = emptySet(),
    val notCollections: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() =
            years.isEmpty() &&
                ratings.isEmpty() &&
                collections.isEmpty() &&
                notYears.isEmpty() &&
                notRatings.isEmpty() &&
                notCollections.isEmpty()

    fun matches(album: Album): Boolean {
        if (years.isNotEmpty() || notYears.isNotEmpty()) {
            val dates = album.dates
            val range = dates?.let { it.min.year..it.max.year }
            if (years.isNotEmpty() && (range == null || years.none { it in range })) return false
            if (range != null && notYears.any { it in range }) return false
        }
        if (ratings.isNotEmpty() || notRatings.isNotEmpty()) {
            val rating = AlbumRatings.get(album) ?: NO_RATING
            if (ratings.isNotEmpty() && rating !in ratings) return false
            if (rating in notRatings) return false
        }
        if (collections.isNotEmpty() || notCollections.isNotEmpty()) {
            val of = AlbumCollections.of(album)
            if (collections.isNotEmpty() && collections.none { it in of }) return false
            if (notCollections.any { it in of }) return false
        }
        return true
    }

    /** Estado de uma opção: 0 = desligada, 1 = incluída, -1 = excluída. */
    fun stateOfYear(year: Int) = if (year in years) 1 else if (year in notYears) -1 else 0

    fun stateOfRating(rating: Int) = if (rating in ratings) 1 else if (rating in notRatings) -1 else 0

    fun stateOfCollection(name: String) =
        if (name in collections) 1 else if (name in notCollections) -1 else 0

    fun withYear(year: Int, state: Int) =
        copy(years = years.with(year, state == 1), notYears = notYears.with(year, state == -1))

    fun withRating(rating: Int, state: Int) =
        copy(
            ratings = ratings.with(rating, state == 1),
            notRatings = notRatings.with(rating, state == -1),
        )

    fun withCollection(name: String, state: Int) =
        copy(
            collections = collections.with(name, state == 1),
            notCollections = notCollections.with(name, state == -1),
        )

    /** Troca o nome de uma coleção renomeada (ou tira, se [new] for null). */
    fun renameCollection(old: String, new: String?) =
        copy(
            collections = collections.renamed(old, new),
            notCollections = notCollections.renamed(old, new),
        )

    private fun <T> Set<T>.with(item: T, present: Boolean) = if (present) this + item else this - item

    private fun Set<String>.renamed(old: String, new: String?) =
        if (old !in this) this else (this - old).let { if (new != null) it + new else it }

    /** Resumo do filtro para o chip e para o nome da fila (ex.: "Primavera Sound 2026 · sem 2025"). */
    fun describe(context: Context): String {
        fun rating(it: Int) =
            when (it) {
                NO_RATING -> context.getString(R.string.lbl_no_rating)
                0 -> context.getString(R.string.lbl_rating_zero)
                else -> context.getString(R.string.fmt_rating_stars, it)
            }
        val order = AlbumCollections.all()
        fun sortedNames(set: Set<String>) = set.sortedBy { order.indexOf(it) }
        val parts = mutableListOf<String>()
        val not = context.getString(R.string.fmt_filter_not)
        if (collections.isNotEmpty()) parts += sortedNames(collections).joinToString(", ")
        if (ratings.isNotEmpty()) parts += ratings.sorted().joinToString(", ") { rating(it) }
        if (years.isNotEmpty()) parts += years.sortedDescending().joinToString(", ")
        if (notCollections.isNotEmpty()) {
            parts += sortedNames(notCollections).joinToString(", ") { not.format(it) }
        }
        if (notRatings.isNotEmpty()) {
            parts += notRatings.sorted().joinToString(", ") { not.format(rating(it)) }
        }
        if (notYears.isNotEmpty()) {
            parts += notYears.sortedDescending().joinToString(", ") { not.format(it.toString()) }
        }
        return parts.joinToString(" · ")
    }

    companion object {
        const val NO_RATING = -1
    }
}
