/*
 * Copyright (c) 2026 Auxio Project
 * SearchHistory.kt is part of Auxio.
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

package org.oxycblt.auxio.search

import android.content.Context

/**
 * Fork pessoal: histórico da busca. Guarda as últimas buscas (a mais recente primeiro, sem repetir,
 * no máximo [MAX]). Uma busca entra quando você abre um resultado ou toca em "buscar" no teclado,
 * não a cada letra digitada.
 */
object SearchHistory {
    private const val PREFS = "search_history"
    private const val KEY = "items"
    private const val MAX = 25

    fun all(context: Context): List<String> =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun save(context: Context, items: List<String>) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, items.take(MAX).joinToString("\n"))
            .apply()
    }

    fun add(context: Context, query: String?) {
        val q = query?.replace('\n', ' ')?.trim().orEmpty()
        if (q.isEmpty()) return
        save(context, listOf(q) + all(context).filterNot { it.equals(q, ignoreCase = true) })
    }

    fun remove(context: Context, query: String) {
        save(context, all(context).filterNot { it == query })
    }

    fun clear(context: Context) = save(context, emptyList())
}
