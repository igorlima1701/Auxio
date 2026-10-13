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
        ItemTouchHelper(Callback()).attachToRecyclerView(recycler)
    }

    fun songOf(holder: RecyclerView.ViewHolder) =
        holder.itemView.getTag(R.id.tag_preview_song) as? Song

    /** Toca a prévia da música da linha e devolve a linha ao lugar. */
    fun preview(holder: RecyclerView.ViewHolder) {
        songOf(holder)?.let(PreviewPlayer::start)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) holder.bindingAdapter?.notifyItemChanged(pos)
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
