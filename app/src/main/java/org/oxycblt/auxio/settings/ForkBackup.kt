/*
 * Copyright (c) 2026 Auxio Project
 * ForkBackup.kt is part of Auxio.
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

package org.oxycblt.auxio.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.playback.PlayHistory

/**
 * Fork pessoal: cópia de segurança num arquivo .json com o histórico de execuções, as configurações
 * do app, as notas e as coleções dos álbuns. Importar junta o histórico (sem duplicar) e põe as
 * configurações, notas e coleções do arquivo por cima das atuais.
 */
object ForkBackup {
    data class Result(val plays: Int, val settings: Int)

    private const val FORMAT = "auxio-igor-backup"

    /** Arquivos de preferências incluídos (o padrão do app entra à parte). */
    private val PREFS = listOf("album_ratings", "album_collections", "lyrics_search")
    private const val DEFAULT = "__default"

    private fun prefsFor(context: Context, name: String): SharedPreferences =
        if (name == DEFAULT) PreferenceManager.getDefaultSharedPreferences(context)
        else context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Grava a cópia em [uri]. Roda fora da tela principal. */
    fun export(context: Context, uri: Uri): Result {
        val plays = PlayHistory.onDatabase { PlayHistory.exportRows() }
        val prefs = JSONObject()
        var settings = 0
        for (name in listOf(DEFAULT) + PREFS) {
            val obj = JSONObject()
            for ((key, value) in prefsFor(context, name).all) {
                val typed =
                    when (value) {
                        is String -> JSONObject().put("t", "s").put("v", value)
                        is Int -> JSONObject().put("t", "i").put("v", value)
                        is Long -> JSONObject().put("t", "l").put("v", value)
                        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                        is Boolean -> JSONObject().put("t", "b").put("v", value)
                        is Set<*> ->
                            JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                        else -> null
                    } ?: continue
                obj.put(key, typed)
                settings++
            }
            prefs.put(name, obj)
        }
        val root =
            JSONObject()
                .put("format", FORMAT)
                .put("version", 1)
                .put("exportedAt", System.currentTimeMillis())
                .put("plays", plays)
                .put("prefs", prefs)
        val out =
            context.contentResolver.openOutputStream(uri, "wt")
                ?: error("não foi possível criar o arquivo")
        out.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        return Result(plays.length(), settings)
    }

    /** Lê a cópia de [uri] e aplica. Roda fora da tela principal. */
    fun import(context: Context, uri: Uri): Result {
        val text =
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: error("não foi possível abrir o arquivo")
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) { "o arquivo não é uma cópia do Auxio+" }
        val plays = root.optJSONArray("plays")?.let { PlayHistory.onDatabase { PlayHistory.importRows(it) } } ?: 0
        var settings = 0
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        for (name in prefs.keys()) {
            if (name != DEFAULT && name !in PREFS) continue
            val obj = prefs.optJSONObject(name) ?: continue
            val editor = prefsFor(context, name).edit()
            for (key in obj.keys()) {
                val typed = obj.optJSONObject(key) ?: continue
                when (typed.optString("t")) {
                    "s" -> editor.putString(key, typed.optString("v"))
                    "i" -> editor.putInt(key, typed.optInt("v"))
                    "l" -> editor.putLong(key, typed.optLong("v"))
                    "f" -> editor.putFloat(key, typed.optDouble("v").toFloat())
                    "b" -> editor.putBoolean(key, typed.optBoolean("v"))
                    "set" -> {
                        val array = typed.optJSONArray("v") ?: JSONArray()
                        editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                    }
                    else -> continue
                }
                settings++
            }
            editor.commit()
        }
        return Result(plays, settings)
    }
}
