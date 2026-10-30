/*
 * Copyright (c) 2026 Auxio Project
 * GenreTreeDialog.kt is part of Auxio.
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

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.resolve
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.tag.Name

/**
 * Fork pessoal: a árvore de gêneros de um álbum. Mostra os gêneros dele (em destaque) e, acima de
 * cada um, os gêneros pais até o primeiro nível. Tocar em qualquer gênero abre os álbuns dele.
 */
object GenreTreeDialog {
    private class Line(val key: String, val name: String, val depth: Int, val own: Boolean)

    fun show(activity: FragmentActivity, album: Album) {
        val context = activity.applicationContext
        activity.lifecycleScope.launch {
            val lines = withContext(Dispatchers.Default) { build(context, album) }
            render(activity, album, lines)
        }
    }

    private fun build(context: android.content.Context, album: Album): List<Line> {
        // Gêneros do álbum (as tags de todas as faixas, separando "A; B").
        val own = LinkedHashMap<String, String>()
        for (genre in album.songs.flatMap { it.genres }.distinct()) {
            val raw = (genre.name as? Name.Known)?.raw ?: continue
            for (part in GenreTree.split(raw)) own.putIfAbsent(GenreTree.key(part), part)
        }
        if (own.isEmpty()) return emptyList()

        // Todos os gêneros do caminho: os do álbum e os ancestrais deles.
        val visible = LinkedHashSet<String>()
        fun climb(k: String) {
            if (!visible.add(k)) return
            GenreTree.parentsOf(context, k).forEach(::climb)
        }
        own.keys.forEach(::climb)
        val children = HashMap<String, MutableList<String>>()
        val roots = mutableListOf<String>()
        for (k in visible) {
            val ps = GenreTree.parentsOf(context, k).filter { it in visible }
            if (ps.isEmpty()) roots += k else ps.forEach { children.getOrPut(it) { mutableListOf() } += k }
        }
        fun name(k: String) = own[k] ?: GenreTree.nameOf(context, k) ?: k

        val out = mutableListOf<Line>()
        fun add(k: String, depth: Int, path: Set<String>) {
            out += Line(k, name(k), depth, k in own)
            children[k]
                .orEmpty()
                .filter { it !in path }
                .sortedBy { GenreTree.key(name(it)) }
                .forEach { add(it, depth + 1, path + k) }
        }
        roots.sortedBy { GenreTree.key(name(it)) }.forEach { add(it, 0, emptySet()) }
        return out
    }

    private fun render(activity: FragmentActivity, album: Album, lines: List<Line>) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val accent = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, 0)
        val root =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(8))
            }
        var dialog: AlertDialog? = null
        root.addView(
            TextView(activity).apply {
                setText(if (lines.isEmpty()) R.string.lng_genre_tree_empty else R.string.lng_genre_tree_hint)
                alpha = 0.7f
                setPadding(dp(12), 0, dp(12), dp(8))
            }
        )
        for (line in lines) {
            root.addView(
                TextView(activity).apply {
                    text = line.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(44)
                    setPadding(dp(12) + line.depth * dp(20), 0, dp(12), 0)
                    if (line.own) {
                        setTextColor(accent)
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    val ripple = TypedValue()
                    activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                    setBackgroundResource(ripple.resourceId)
                    setOnClickListener {
                        dialog?.dismiss()
                        GenreTree.requestOpen(line.key)
                    }
                }
            )
        }
        dialog =
            MaterialAlertDialogBuilder(activity)
                .setTitle(album.name.resolve(activity))
                .setView(ScrollView(activity).apply { addView(root) })
                .setPositiveButton(R.string.lbl_ok, null)
                .show()
    }
}
