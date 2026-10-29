/*
 * Copyright (c) 2026 Auxio Project
 * GenreTree.kt is part of Auxio.
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

package org.oxycblt.auxio.home.genre

import android.content.Context
import java.text.Normalizer
import org.oxycblt.auxio.music.resolve
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name
import timber.log.Timber as L

/**
 * Fork pessoal: árvore de gêneros do RateYourMusic (arquivo assets/genre_tree.tsv, uma linha
 * "gênero<TAB>pai"). Os gêneros vêm das tags das músicas; a árvore só diz quem é pai de quem.
 *
 * Na aba aparecem só os gêneros que têm música e os pais deles. Um gênero pai inclui as músicas de
 * todos os filhos. Gênero das tags que não está na árvore fica no primeiro nível. Um gênero com mais
 * de um pai aparece embaixo de cada um.
 */
object GenreTree {
    /** Um gênero da árvore montada para a biblioteca. */
    class Node(
        val key: String,
        val name: String,
        /** O gênero da biblioteca com este nome (null = só existe na árvore, como "Rock"). */
        val genre: Genre?,
        val children: List<Node>,
        /** Músicas deste gênero e de todos os que estão abaixo dele. */
        val songs: List<Song>,
    ) {
        val albums: List<Album> by lazy { songs.map { it.album }.distinct() }
    }

    private const val UNKNOWN_KEY = "\u0000sem-genero"

    @Volatile private var parents: Map<String, List<String>>? = null
    @Volatile private var names: Map<String, String> = emptyMap()

    fun key(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC)
            .lowercase()
            .replace('’', '\'')
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Lê a árvore (uma vez só). */
    @Synchronized
    private fun load(context: Context): Map<String, List<String>> {
        parents?.let {
            return it
        }
        val map = HashMap<String, MutableList<String>>()
        val display = HashMap<String, String>()
        try {
            context.assets.open("genre_tree.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    val child = (if (tab < 0) line else line.substring(0, tab)).trim()
                    val parent = if (tab < 0) "" else line.substring(tab + 1).trim()
                    if (child.isEmpty()) continue
                    val ck = key(child)
                    display.putIfAbsent(ck, child)
                    val list = map.getOrPut(ck) { mutableListOf() }
                    if (parent.isNotEmpty()) {
                        val pk = key(parent)
                        display.putIfAbsent(pk, parent)
                        map.getOrPut(pk) { mutableListOf() }
                        if (pk !in list) list += pk
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e, "Não consegui ler a árvore de gêneros")
        }
        names = display
        parents = map
        return map
    }

    /** Monta a árvore para os gêneros da biblioteca. Rodar fora da main. */
    fun build(context: Context, genres: Collection<Genre>): List<Node> {
        val tree = load(context)
        // Gêneros da biblioteca, pela chave do nome.
        val library = HashMap<String, Genre>()
        for (genre in genres) {
            val k =
                when (val name = genre.name) {
                    is Name.Known -> key(name.raw)
                    else -> UNKNOWN_KEY
                }
            library.putIfAbsent(k, genre)
        }

        // Visíveis: os da biblioteca e todos os ancestrais deles.
        val visible = HashSet<String>()
        fun climb(k: String) {
            if (!visible.add(k)) return
            for (p in tree[k].orEmpty()) climb(p)
        }
        library.keys.forEach(::climb)

        // Filhos visíveis de cada visível.
        val children = HashMap<String, MutableList<String>>()
        val roots = mutableListOf<String>()
        for (k in visible) {
            val ps = tree[k].orEmpty().filter { it in visible }
            if (ps.isEmpty()) roots += k else ps.forEach { children.getOrPut(it) { mutableListOf() } += k }
        }

        // Nós (um gênero com dois pais é o mesmo nó nos dois lugares).
        val built = HashMap<String, Node>()
        fun node(k: String, path: Set<String>): Node {
            built[k]?.let {
                return it
            }
            val kids =
                children[k]
                    .orEmpty()
                    .filter { it !in path } // proteção contra laços na árvore
                    .map { node(it, path + k) }
            val genre = library[k]
            val songs = LinkedHashSet<Song>()
            genre?.songs?.let(songs::addAll)
            kids.forEach { songs.addAll(it.songs) }
            val name =
                genre?.name?.resolve(context)
                    ?: names[k]
                    ?: k
            return Node(k, name, genre, kids.sortedBy { key(it.name) }, songs.toList()).also {
                built[k] = it
            }
        }
        return roots.map { node(it, emptySet()) }.sortedWith(
            compareBy<Node> { it.key == UNKNOWN_KEY }.thenBy { key(it.name) }
        )
    }

    /** Acha o nó [key] na árvore montada. */
    fun find(roots: List<Node>, key: String): Node? {
        val seen = HashSet<String>()
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.key == key) return n
            if (seen.add(n.key)) stack.addAll(n.children)
        }
        return null
    }
}
