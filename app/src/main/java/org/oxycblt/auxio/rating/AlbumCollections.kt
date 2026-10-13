/*
 * Copyright (c) 2026 Auxio Project
 * AlbumCollections.kt is part of Auxio.
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
 * Fork pessoal: coleções de álbuns criadas pelo usuário (ex.: "Primavera Sound 2026").
 *
 * Ficam em memória e num arquivo de preferências próprio. Cada mudança também é espelhada no
 * campo ALBUMCOLLECTION das faixas (ver [AlbumRatingTagSync]), com os nomes separados por "; ".
 */
object AlbumCollections {
    const val SEPARATOR = "; "
    private const val KEY_NAMES = "__names"
    private const val ALBUM_PREFIX = "a:"
    private const val PENDING_PREFIX = "pending:"

    private lateinit var prefs: SharedPreferences
    private val names = mutableListOf<String>()
    private val members = ConcurrentHashMap<String, Set<String>>()
    private val pending = ConcurrentHashMap<String, Set<String>>()

    /** Álbuns da biblioteca atual, pelo identificador (para regravar as tags). */
    @Volatile private var library: Map<String, Album> = emptyMap()

    /** Muda sempre que alguma coleção muda. */
    val version: StateFlow<Long>
        field = MutableStateFlow(0L)

    /** Avisado com os álbuns cujas coleções mudaram (para gravar a tag). */
    @Volatile var onUserChange: ((List<Album>) -> Unit)? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("album_collections", Context.MODE_PRIVATE)
        prefs.getString(KEY_NAMES, null)?.let { stored ->
            names += stored.split('\n').filter { it.isNotBlank() }
        }
        for ((key, value) in prefs.all) {
            if (value !is String) continue
            val set = value.split('\n').filter { it.isNotBlank() }.toSet()
            when {
                key.startsWith(ALBUM_PREFIX) -> members[key.removePrefix(ALBUM_PREFIX)] = set
                key.startsWith(PENDING_PREFIX) -> pending[key] = set
            }
        }
    }

    @Synchronized fun all(): List<String> = names.toList()

    fun hasAny(): Boolean = names.isNotEmpty()

    fun of(album: Album): Set<String> = members[album.uid.toString()] ?: emptySet()

    /** Valor do campo ALBUMCOLLECTION de [album], na ordem das coleções. */
    @Synchronized
    fun tagValue(album: Album): String {
        val set = of(album)
        return names.filter { it in set }.joinToString(SEPARATOR)
    }

    /** Nome válido: sem ";" (separador da tag), sem quebra de linha e não vazio. */
    fun clean(name: String): String? {
        val trimmed = name.replace('\n', ' ').trim()
        if (trimmed.isEmpty() || trimmed.contains(';')) return null
        return trimmed
    }

    @Synchronized
    fun exists(name: String) = names.any { it.equals(name, ignoreCase = true) }

    /** Cria a coleção (se ainda não existir). Retorna o nome guardado, ou null se inválido. */
    @Synchronized
    fun create(name: String): String? {
        val clean = clean(name) ?: return null
        names.firstOrNull { it.equals(clean, ignoreCase = true) }?.let { return it }
        names += clean
        saveNames()
        bump()
        return clean
    }

    /** Põe ([add] = true) ou tira os [albums] da coleção [name]. */
    fun setMember(albums: Collection<Album>, name: String, add: Boolean) {
        val changed = mutableListOf<Album>()
        synchronized(this) {
            if (names.none { it == name }) return
            val editor = prefs.edit()
            for (album in albums) {
                val key = album.uid.toString()
                val current = members[key] ?: emptySet()
                val next = if (add) current + name else current - name
                if (next == current) continue
                store(editor, key, next)
                changed += album
            }
            editor.apply()
        }
        if (changed.isEmpty()) return
        bump()
        onUserChange?.invoke(changed)
    }

    @Synchronized
    fun rename(old: String, new: String): Boolean {
        val clean = clean(new) ?: return false
        val index = names.indexOf(old)
        if (index < 0) return false
        if (clean == old) return true
        if (names.any { it != old && it.equals(clean, ignoreCase = true) }) return false
        names[index] = clean
        saveNames()
        val changed = replaceEverywhere(old, clean)
        bump()
        onUserChange?.invoke(changed)
        return true
    }

    @Synchronized
    fun delete(name: String) {
        if (!names.remove(name)) return
        saveNames()
        val changed = replaceEverywhere(name, null)
        bump()
        onUserChange?.invoke(changed)
    }

    /** Move a coleção [name] uma posição para cima ou para baixo na lista. */
    @Synchronized
    fun move(name: String, delta: Int) {
        val index = names.indexOf(name)
        val target = index + delta
        if (index < 0 || target !in names.indices) return
        names.removeAt(index)
        names.add(target, name)
        saveNames()
        bump()
    }

    private fun replaceEverywhere(old: String, new: String?): List<Album> {
        val editor = prefs.edit()
        val changed = mutableListOf<Album>()
        for ((key, set) in members.entries.toList()) {
            if (old !in set) continue
            val next = (set - old).let { if (new != null) it + new else it }
            store(editor, key, next)
            library[key]?.let { changed += it }
        }
        editor.apply()
        return changed
    }

    private fun store(editor: SharedPreferences.Editor, key: String, set: Set<String>) {
        if (set.isEmpty()) {
            members.remove(key)
            editor.remove(ALBUM_PREFIX + key)
        } else {
            members[key] = set
            editor.putString(ALBUM_PREFIX + key, set.joinToString("\n"))
        }
    }

    private fun saveNames() {
        prefs.edit().putString(KEY_NAMES, names.joinToString("\n")).apply()
    }

    private fun bump() {
        version.value = version.value + 1
    }

    /** Recupera as coleções lidas da tag de [album] (numa instalação nova). */
    @Synchronized
    fun restore(album: Album, tag: String) {
        val found = tag.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (found.isEmpty()) return
        val editor = prefs.edit()
        val stored = found.map { n -> names.firstOrNull { it.equals(n, true) } ?: n.also { names += it } }
        val key = album.uid.toString()
        store(editor, key, (members[key] ?: emptySet()) + stored)
        editor.putString(KEY_NAMES, names.joinToString("\n"))
        editor.apply()
        bump()
    }

    /** Guarda as coleções de [album] para reaplicar depois de uma edição de tags. */
    fun rememberForRetag(album: Album) {
        val set = of(album)
        if (set.isEmpty()) return
        val song = album.songs.firstOrNull() ?: return
        val key = PENDING_PREFIX + song.uri
        pending[key] = set
        prefs.edit().putString(key, set.joinToString("\n")).apply()
    }

    /** Atualiza a biblioteca conhecida e reaplica coleções pendentes aos álbuns novos. */
    fun resolvePending(albums: List<Album>) {
        library = albums.associateBy { it.uid.toString() }
        if (pending.isEmpty()) return
        var changed = false
        synchronized(this) {
            val editor = prefs.edit()
            for (album in albums) {
                for (song in album.songs) {
                    val key = PENDING_PREFIX + song.uri
                    val set = pending.remove(key) ?: continue
                    editor.remove(key)
                    val albumKey = album.uid.toString()
                    if (!members.containsKey(albumKey)) {
                        store(editor, albumKey, set.filter { it in names }.toSet())
                        changed = true
                    }
                }
            }
            editor.apply()
        }
        if (changed) bump()
    }

    /** Álbuns de [albums] em alguma coleção (para conferir as tags). */
    fun withCollections(albums: List<Album>): List<Album> =
        albums.filter { members.containsKey(it.uid.toString()) }
}
