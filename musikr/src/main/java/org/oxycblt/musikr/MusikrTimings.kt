/*
 * Copyright (c) 2026 Auxio Project
 * MusikrTimings.kt is part of Auxio.
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
 
package org.oxycblt.musikr

/**
 * Fork pessoal: quanto tempo cada etapa da última leitura da biblioteca levou (em ms), para achar
 * o que deixa a abertura lenta.
 */
object MusikrTimings {
    /** Até a lista de arquivos (e o cache de tags) estar toda lida. */
    @Volatile var exploreMs = 0L
    /** Até todas as músicas estarem prontas para montar a biblioteca. */
    @Volatile var extractMs = 0L
    /** Leitura inteira, até a biblioteca montada (álbuns, artistas e gêneros). */
    @Volatile var totalMs = 0L
    @Volatile var songs = 0
}
