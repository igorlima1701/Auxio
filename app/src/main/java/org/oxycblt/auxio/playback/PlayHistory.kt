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

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Fork pessoal: histórico de execuções. Cada vez que uma música é ouvida por metade da duração (ou
 * 4 minutos, o que vier primeiro) entra uma linha no banco "play_history.db", com a data e quanto
 * tempo foi ouvido. Não mexe nos arquivos de música.
 *
 * Os totais por música (quantas vezes, última vez) ficam em memória para as listas abrirem na hora.
 */
object PlayHistory {
    data class Entry(val count: Int, val lastPlayedMs: Long)

    private const val TABLE = "plays"
    private val io = Executors.newSingleThreadExecutor()
    private var helper: Helper? = null
    private val totals = ConcurrentHashMap<String, Entry>()
    @Volatile private var loaded = false

    /** Muda sempre que o histórico muda (para as playlists automáticas se refazerem). */
    val version: StateFlow<Long>
        field = MutableStateFlow(0L)

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "play_history.db", null, 1) {
        val app: Context = context.applicationContext

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "song TEXT NOT NULL, " +
                    "at INTEGER NOT NULL, " +
                    "listened INTEGER NOT NULL DEFAULT 0, " +
                    "path TEXT)"
            )
            db.execSQL("CREATE INDEX plays_song ON $TABLE(song)")
            db.execSQL("CREATE INDEX plays_at ON $TABLE(at)")
            migrateOldPrefs(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

        /**
         * Traz o histórico antigo (só "quantas vezes" e "última vez"): cada vez vira uma linha com
         * a data da última vez e tempo ouvido -1 (desconhecido).
         */
        private fun migrateOldPrefs(db: SQLiteDatabase) {
            val prefs = app.getSharedPreferences("play_history", Context.MODE_PRIVATE)
            var rows = 0
            db.beginTransaction()
            try {
                for ((uid, value) in prefs.all) {
                    val parts = (value as? String)?.split('|') ?: continue
                    val count = parts.getOrNull(0)?.toIntOrNull() ?: continue
                    val last = parts.getOrNull(1)?.toLongOrNull() ?: continue
                    repeat(count.coerceIn(0, 10000)) {
                        db.insert(
                            TABLE,
                            null,
                            ContentValues().apply {
                                put("song", uid)
                                put("at", last)
                                put("listened", -1L)
                            },
                        )
                        rows++
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            L.d("Histórico antigo migrado: $rows execuções")
        }
    }

    fun init(context: Context) {
        if (helper != null) return
        val h = Helper(context.applicationContext)
        helper = h
        io.execute { reloadTotals() }
    }

    private fun reloadTotals() {
        val db = helper?.readableDatabase ?: return
        val fresh = HashMap<String, Entry>()
        db.rawQuery("SELECT song, COUNT(*), MAX(at) FROM $TABLE GROUP BY song", null).use { c ->
            while (c.moveToNext()) fresh[c.getString(0)] = Entry(c.getInt(1), c.getLong(2))
        }
        totals.clear()
        totals.putAll(fresh)
        loaded = true
        version.value = version.value + 1
    }

    val isLoaded: Boolean
        get() = loaded

    fun get(uid: Music.UID): Entry? = totals[uid.toString()]

    /** Todos os totais por música (uid -> total). */
    fun all(): Map<String, Entry> = totals

    /** Conta uma execução de [song], ouvida por [listenedMs]. */
    fun record(song: Song, listenedMs: Long) {
        val key = song.uid.toString()
        val now = System.currentTimeMillis()
        val old = totals[key]
        totals[key] = Entry((old?.count ?: 0) + 1, now)
        version.value = version.value + 1
        val path = song.path.toString()
        io.execute {
            try {
                helper
                    ?.writableDatabase
                    ?.insert(
                        TABLE,
                        null,
                        ContentValues().apply {
                            put("song", key)
                            put("at", now)
                            put("listened", listenedMs)
                            put("path", path)
                        },
                    )
            } catch (e: Exception) {
                L.w(e, "Não consegui gravar a execução")
            }
        }
        L.d("Execução contada: ${song.path} (${listenedMs / 1000}s)")
    }

    /** Apaga o histórico de [uid]. */
    fun clear(uid: Music.UID) {
        val key = uid.toString()
        totals.remove(key)
        version.value = version.value + 1
        io.execute {
            try {
                helper?.writableDatabase?.delete(TABLE, "song = ?", arrayOf(key))
            } catch (e: Exception) {
                L.w(e, "Não consegui apagar o histórico")
            }
        }
    }

    // --- EXPORTAR / IMPORTAR ---

    /** Todas as execuções, para o backup. Roda fora da tela principal. */
    fun exportRows(): JSONArray {
        val out = JSONArray()
        val db = helper?.readableDatabase ?: return out
        db.rawQuery("SELECT song, at, listened, path FROM $TABLE ORDER BY at", null).use { c ->
            while (c.moveToNext()) {
                out.put(
                    JSONObject().apply {
                        put("song", c.getString(0))
                        put("at", c.getLong(1))
                        put("listened", c.getLong(2))
                        if (!c.isNull(3)) put("path", c.getString(3))
                    }
                )
            }
        }
        return out
    }

    /**
     * Junta as execuções do backup às que já existem (as repetidas, mesma música no mesmo instante,
     * ficam de fora). Retorna quantas entraram. Roda fora da tela principal.
     */
    fun importRows(rows: JSONArray): Int {
        val db = helper?.writableDatabase ?: return 0
        val existing = HashSet<String>()
        db.rawQuery("SELECT song, at FROM $TABLE", null).use { c ->
            while (c.moveToNext()) existing += c.getString(0) + "|" + c.getLong(1)
        }
        var added = 0
        db.beginTransaction()
        try {
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val song = o.optString("song").ifEmpty { null } ?: continue
                val at = o.optLong("at", 0L)
                if (at <= 0L) continue
                if (!existing.add("$song|$at")) continue
                db.insert(
                    TABLE,
                    null,
                    ContentValues().apply {
                        put("song", song)
                        put("at", at)
                        put("listened", o.optLong("listened", -1L))
                        if (o.has("path")) put("path", o.optString("path"))
                    },
                )
                added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        reloadTotals()
        return added
    }

    /** Roda [block] na fila do banco e espera o resultado. */
    fun <T> onDatabase(block: () -> T): T = io.submit(java.util.concurrent.Callable { block() }).get()
}
