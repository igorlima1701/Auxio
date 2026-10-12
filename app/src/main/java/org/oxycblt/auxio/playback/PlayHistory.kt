/*
 * Copyright (c) 2026 Auxio Project
 * PlayHistory.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback

import android.content.Context
import android.content.SharedPreferences
import org.oxycblt.musikr.Music

/**
 * Fork pessoal: quantas vezes cada música foi tocada até o fim e quando foi a última vez. Fica num
 * arquivo de preferências próprio; não mexe nos arquivos de música.
 */
object PlayHistory {
    data class Entry(val count: Int, val lastPlayedMs: Long)

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("play_history", Context.MODE_PRIVATE)
        }
    }

    fun get(uid: Music.UID): Entry? {
        val raw = prefs?.getString(uid.toString(), null) ?: return null
        val parts = raw.split('|')
        val count = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val last = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return Entry(count, last)
    }

    /** Conta uma reprodução completa de [uid]. */
    fun record(uid: Music.UID) {
        val p = prefs ?: return
        val count = (get(uid)?.count ?: 0) + 1
        p.edit().putString(uid.toString(), "$count|${System.currentTimeMillis()}").apply()
    }

    fun clear(uid: Music.UID) {
        prefs?.edit()?.remove(uid.toString())?.apply()
    }
}
