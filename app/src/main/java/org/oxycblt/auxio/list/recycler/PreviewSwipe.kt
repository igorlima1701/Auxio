/*
 * Copyright (c) 2026 Auxio Project
 * PreviewSwipe.kt is part of Auxio.
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

package org.oxycblt.auxio.list.recycler

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.PreviewPlayer
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: arrastar uma música para a esquerda toca a prévia dela; a linha volta para o lugar.
 * Vale nas listas comuns (álbum, artista, gênero, músicas, busca). Na fila e na playlist em edição
 * quem cuida disso é o [MaterialDragCallback].
 */
object PreviewSwipe {
    fun attach(recycler: RecyclerView) {
        val helper = ItemTouchHelper(Callback())
        helper.attachToRecyclerView(recycler)
        register(recycler, helper)
    }

    /** Guarda os ItemTouchHelper da lista, para soltar a linha arrastada depois da prévia. */
    fun register(recycler: RecyclerView, helper: ItemTouchHelper) {
        @Suppress("UNCHECKED_CAST")
        val list = (recycler.getTag(R.id.tag_touch_helpers) as? MutableList<ItemTouchHelper>)
            ?: mutableListOf<ItemTouchHelper>().also { recycler.setTag(R.id.tag_touch_helpers, it) }
        if (helper !in list) list.add(helper)
    }

    fun songOf(holder: RecyclerView.ViewHolder) =
        holder.itemView.getTag(R.id.tag_preview_song) as? Song

    /** Toca a prévia da música da linha e devolve a linha ao lugar. */
    fun preview(holder: RecyclerView.ViewHolder) {
        songOf(holder)?.let(PreviewPlayer::start)
        val recycler = holder.itemView.parent as? RecyclerView
        val pos = holder.bindingAdapterPosition
        val adapter = holder.bindingAdapter
        // O ItemTouchHelper guarda a linha "arrastada para fora" até ela sair da tela. Religá-lo à
        // lista descarta esse estado e devolve a linha ao lugar na hora.
        recycler?.post {
            @Suppress("UNCHECKED_CAST")
            val helpers = recycler.getTag(R.id.tag_touch_helpers) as? List<ItemTouchHelper>
            helpers?.forEach {
                it.attachToRecyclerView(null)
                it.attachToRecyclerView(recycler)
            }
            if (holder is MaterialDragCallback.ViewHolder) {
                holder.body.translationX = 0f
                holder.root.translationY = 0f
                holder.delete.visibility = android.view.View.INVISIBLE
            } else {
                ItemTouchHelper.Callback.getDefaultUIUtil().clearView(holder.itemView)
            }
            if (pos != RecyclerView.NO_POSITION) adapter?.notifyItemChanged(pos)
        }
    }

    private class Callback : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            if (songOf(viewHolder) == null) return 0
            // Linhas editáveis (fila, playlist em edição) têm o próprio arrastar.
            if (viewHolder is MaterialDragCallback.ViewHolder && viewHolder.enabled) return 0
            return ItemTouchHelper.LEFT
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ) = false

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.3f

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            preview(viewHolder)
        }
    }
}
