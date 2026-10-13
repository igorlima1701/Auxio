/*
 * Copyright (c) 2026 Auxio Project
 * GridSnapshot.kt is part of Auxio.
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

package org.oxycblt.auxio.home.list

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.oxycblt.auxio.databinding.ItemAlbumCoverBinding
import org.oxycblt.auxio.image.coil.GridThumbStore
import org.oxycblt.auxio.image.coil.primaryCover
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Album
import timber.log.Timber as L

/**
 * Fork pessoal: "foto" da grade de álbuns (ordem e miniaturas), guardada sempre que a grade
 * completa muda. Na abertura do app ela aparece na hora, enquanto a biblioteca carrega, e é trocada
 * pela grade de verdade assim que esta fica pronta.
 */
object GridSnapshot {
    data class Entry(val uid: String, val thumb: File?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var cached: List<Entry>? = null
    @Volatile private var lastSavedHash = 0

    private fun file(context: Context) = File(context.filesDir, "grid_snapshot.txt")

    /** Lê a foto guardada (vazia se não houver). */
    fun load(context: Context): List<Entry> {
        cached?.let {
            return it
        }
        val entries =
            try {
                val f = file(context)
                if (!f.exists()) emptyList()
                else
                    f.readLines().mapNotNull { line ->
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@mapNotNull null
                        val path = line.substring(tab + 1)
                        Entry(line.substring(0, tab), if (path.isEmpty()) null else File(path))
                    }
            } catch (e: Exception) {
                L.w("Foto da grade ilegível: $e")
                emptyList()
            }
        cached = entries
        return entries
    }

    /** Guarda a grade completa [albums] com miniaturas de [px] pixels. */
    fun save(context: Context, albums: List<Album>, px: Int, store: GridThumbStore) {
        if (albums.isEmpty() || px <= 0) return
        val app = context.applicationContext
        scope.launch {
            try {
                val lines =
                    albums.map { album ->
                        val thumb = album.primaryCover()?.let { store.file(it, px) }
                        val path = thumb?.takeIf { it.length() > 0 }?.path ?: ""
                        "${album.uid}\t$path"
                    }
                val hash = lines.hashCode()
                if (hash == lastSavedHash) return@launch
                val target = file(app)
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeText(lines.joinToString("\n"))
                if (!tmp.renameTo(target)) tmp.delete()
                lastSavedHash = hash
                cached = null
            } catch (e: Exception) {
                L.w("Não consegui guardar a foto da grade: $e")
            }
        }
    }
}

/** Adapter da foto da grade: só capas. Toque guarda o álbum para abrir quando carregar. */
class GridSnapshotAdapter(
    private val entries: List<GridSnapshot.Entry>,
    private val cellPx: () -> Int,
    private val onTap: (String) -> Unit,
) : RecyclerView.Adapter<GridSnapshotAdapter.Holder>() {
    class Holder(val binding: ItemAlbumCoverBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAlbumCoverBinding.inflate(parent.context.inflater, parent, false))

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        val cell = cellPx()
        val root = holder.binding.root
        if (cell > 0) {
            val params = root.layoutParams
            if (params.height != cell) {
                params.height = cell
                root.layoutParams = params
            }
            val inner = cell - root.paddingTop - root.paddingBottom
            val coverParams = holder.binding.albumCover.layoutParams
            if (coverParams.width != inner || coverParams.height != inner) {
                coverParams.width = inner
                coverParams.height = inner
                holder.binding.albumCover.layoutParams = coverParams
            }
        }
        root.setOnClickListener { onTap(entry.uid) }
        holder.binding.albumCover.bindFile(entry.thumb)
    }
}
