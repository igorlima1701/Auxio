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
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.PlayHistory
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: playlists automáticas a partir do histórico de execuções ([PlayHistory]), de
 * músicas ou de álbuns. Um álbum conta como tocado se alguma faixa dele foi tocada; a última vez
 * dele é a da faixa tocada mais recentemente, e o total é a soma das faixas.
 */
object AutoPlaylists {
    enum class Kind {
        RECENT,
        MOST,
        NOT_PLAYED,
    }

    /** Período de "Não tocadas": null = nunca tocadas; N = não tocadas nos últimos N dias. */
    val PERIODS: List<Int?> = listOf(null, 365, 60, 30, 15, 7, 3)

    data class Row(val music: Music, val count: Int, val lastMs: Long?)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun songs(context: Context, library: Library, kind: Kind, days: Int?): List<Row> {
        val totals = PlayHistory.all()
        val rows =
            library.songs.map { song ->
                val e = totals[song.uid.toString()]
                Row(song, e?.count ?: 0, e?.lastPlayedMs)
            }
        return order(context, rows, kind, days) { (it.music as Song).album }
    }

    fun albums(context: Context, library: Library, kind: Kind, days: Int?): List<Row> {
        val totals = PlayHistory.all()
        val rows =
            library.albums.map { album ->
                var count = 0
                var last: Long? = null
                for (song in album.songs) {
                    val e = totals[song.uid.toString()] ?: continue
                    count += e.count
                    if (last == null || e.lastPlayedMs > last) last = e.lastPlayedMs
                }
                Row(album, count, last)
            }
        return order(context, rows, kind, days) { it.music as Album }
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
        }
    }
}
