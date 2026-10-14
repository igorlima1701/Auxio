/*
 * Copyright (c) 2026 Auxio Project
 * AutoPlaylists.kt is part of Auxio.
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

package org.oxycblt.auxio.home.auto

import android.content.Context
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.PlayHistory
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: playlists automáticas a partir do histórico de execuções ([PlayHistory]), de
 * músicas ou de álbuns.
 * - Tocadas recentemente / Mais tocadas: um álbum entra assim que alguma faixa dele é tocada; a
 *   última vez dele é a da faixa tocada mais recentemente, e o total é a soma das faixas.
 * - Não tocadas: um álbum só sai da lista quando TODAS as faixas dele foram tocadas no período.
 */
object AutoPlaylists {
    enum class Kind {
        ALL,
        ADDED,
        RECENT,
        MOST,
        NOT_PLAYED,
    }

    /** Período de "Não tocadas": null = nunca tocadas; N = não tocadas nos últimos N dias. */
    val PERIODS: List<Int?> = listOf(null, 365, 60, 30, 15, 7, 3)

    /** Período de "Adicionadas recentemente": N = nos últimos N dias; null = todas. */
    val ADDED_PERIODS: List<Int?> = listOf(7, 30, 90, 365, null)

    /**
     * Uma linha. [played] e [total] só valem para álbuns em "Não tocadas" (faixas tocadas no
     * período / faixas do álbum).
     */
    data class Row(
        val music: Music,
        val count: Int,
        val lastMs: Long?,
        val played: Int = 0,
        val total: Int = 0,
    )

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun songs(
        context: Context,
        library: Library,
        settings: ListSettings,
        kind: Kind,
        days: Int?,
    ): List<Row> {
        val totals = PlayHistory.all()
        fun row(song: Song): Row {
            val e = totals[song.uid.toString()]
            return Row(song, e?.count ?: 0, e?.lastPlayedMs)
        }
        return when (kind) {
            Kind.ALL -> settings.songSort.songs(library.songs).map(::row)
            Kind.ADDED -> {
                val limit = days?.let { System.currentTimeMillis() - it * DAY_MS } ?: Long.MIN_VALUE
                library.songs
                    .filter { it.addedMs >= limit }
                    .sortedByDescending { it.addedMs }
                    .map(::row)
            }
            else -> order(context, library.songs.map(::row), kind, days) { (it.music as Song).album }
        }
    }

    fun albums(
        context: Context,
        library: Library,
        settings: ListSettings,
        kind: Kind,
        days: Int?,
    ): List<Row> {
        val totals = PlayHistory.all()
        val limit =
            if (days == null) Long.MIN_VALUE else System.currentTimeMillis() - days * DAY_MS
        fun row(album: Album): Row {
            var count = 0
            var last: Long? = null
            var played = 0
            for (song in album.songs) {
                val e = totals[song.uid.toString()] ?: continue
                count += e.count
                if (last == null || e.lastPlayedMs > last) last = e.lastPlayedMs
                if (e.lastPlayedMs >= limit) played++
            }
            return Row(album, count, last, played, album.songs.size)
        }
        return when (kind) {
            Kind.ALL -> settings.albumSort.albums(library.albums).map(::row)
            Kind.ADDED ->
                library.albums
                    .filter { it.addedMs >= limit }
                    .sortedByDescending { it.addedMs }
                    .map(::row)
            Kind.NOT_PLAYED -> {
                // Falta alguma faixa no período -> o álbum aparece. Os menos ouvidos primeiro.
                val byName = compareBy<Row> { (it.music as Album).name.resolve(context).lowercase() }
                library.albums
                    .map(::row)
                    .filter { it.played < it.total }
                    .sortedWith(
                        compareBy<Row> { it.played.toDouble() / it.total.coerceAtLeast(1) }
                            .thenBy { it.lastMs ?: 0L }
                            .then(byName)
                    )
            }
            else -> order(context, library.albums.map(::row), kind, days) { it.music as Album }
        }
    }

    private inline fun order(
        context: Context,
        rows: List<Row>,
        kind: Kind,
        days: Int?,
        crossinline albumOf: (Row) -> Album,
    ): List<Row> {
        val byName =
            compareBy<Row>(
                { albumOf(it).name.resolve(context).lowercase() },
                { (it.music as? Song)?.disc?.number ?: 0 },
                { (it.music as? Song)?.track ?: 0 },
            )
        return when (kind) {
            Kind.RECENT ->
                rows.filter { it.lastMs != null }.sortedByDescending { it.lastMs }
            Kind.MOST ->
                rows
                    .filter { it.count > 0 }
                    .sortedWith(compareByDescending<Row> { it.count }.thenByDescending { it.lastMs })
            Kind.NOT_PLAYED -> {
                val filtered =
                    if (days == null) {
                        rows.filter { it.lastMs == null }
                    } else {
                        val limit = System.currentTimeMillis() - days * DAY_MS
                        rows.filter { it.lastMs == null || it.lastMs < limit }
                    }
                // Nunca tocadas primeiro; depois as que faz mais tempo.
                filtered.sortedWith(compareBy<Row> { it.lastMs ?: 0L }.then(byName))
            }
            else -> rows
        }
    }
}
