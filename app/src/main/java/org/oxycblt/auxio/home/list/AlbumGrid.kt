/*
 * Copyright (c) 2026 Auxio Project
 * AlbumGrid.kt is part of Auxio.
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

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import org.oxycblt.auxio.databinding.ItemAlbumCoverBinding
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.list.recycler.AlbumViewHolder
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Album

/**
 * [GridLayoutManager] da grade de capas: escolhe o número de colunas pela largura disponível, de
 * modo que cada célula tenha pelo menos [minCellPx].
 */
class AlbumGridLayoutManager(context: Context, private val minCellPx: Int) :
    GridLayoutManager(context, 3) {
    private var lastWidth = -1

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        if (width > 0 && width != lastWidth) {
            val totalSpace = width - paddingLeft - paddingRight
            spanCount = max(2, totalSpace / minCellPx)
            lastWidth = width
        }
        super.onLayoutChildren(recycler, state)
    }

    /**
     * Pré-carregamento na rolagem: monta e carrega as capas de uma tela inteira acima e abaixo da
     * área visível, para elas já estarem prontas quando chegarem à tela.
     */
    override fun calculateExtraLayoutSpace(
        state: RecyclerView.State,
        extraLayoutSpace: IntArray,
    ) {
        extraLayoutSpace[0] = height
        extraLayoutSpace[1] = height
    }

    /** Lado de uma célula em pixels, ou 0 se ainda não houve layout. */
    fun cellSize(): Int {
        if (width <= 0) return 0
        return (width - paddingLeft - paddingRight) / spanCount
    }
}

/**
 * Célula da grade: só a capa do álbum. Toque abre o álbum; toque longo abre o menu do álbum.
 *
 * O tamanho da capa é fixado em pixels antes do bind, para que o carregamento da imagem comece
 * imediatamente (sem esperar um layout) e sempre com o mesmo tamanho, o que permite reaproveitar o
 * cache de memória e pré-carregar capas.
 */
class AlbumCoverViewHolder private constructor(private val binding: ItemAlbumCoverBinding) :
    SelectionIndicatorAdapter.ViewHolder(binding.root) {

    fun bind(album: Album, listener: SelectableListListener<Album>, cellPx: Int) {
        ensureSize(cellPx)
        listener.bind(album, this)
        binding.root.setOnLongClickListener {
            listener.onOpenMenu(album)
            true
        }
        binding.albumCover.bind(album)
    }

    private fun ensureSize(cellPx: Int) {
        if (cellPx <= 0) return
        val root = binding.root
        val rootParams = root.layoutParams
        if (rootParams.height != cellPx) {
            rootParams.height = cellPx
            root.layoutParams = rootParams
        }
        val inner = cellPx - root.paddingTop - root.paddingBottom
        val coverParams = binding.albumCover.layoutParams
        if (coverParams.width != inner || coverParams.height != inner) {
            coverParams.width = inner
            coverParams.height = inner
            binding.albumCover.layoutParams = coverParams
        }
    }

    override fun updatePlayingIndicator(isActive: Boolean, isPlaying: Boolean) {
        binding.root.isSelected = isActive
        binding.albumCover.setPlaying(isPlaying)
    }

    override fun updateSelectionIndicator(isSelected: Boolean) {
        binding.root.isActivated = isSelected
    }

    companion object {
        fun from(parent: ViewGroup) =
            AlbumCoverViewHolder(
                ItemAlbumCoverBinding.inflate(parent.context.inflater, parent, false)
            )

        val DIFF_CALLBACK = AlbumViewHolder.DIFF_CALLBACK
    }
}

/** Adapter da grade de capas de álbuns. */
class AlbumCoverAdapter(private val listener: SelectableListListener<Album>) :
    SelectionIndicatorAdapter<Album, AlbumCoverViewHolder>(AlbumCoverViewHolder.DIFF_CALLBACK) {
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        AlbumCoverViewHolder.from(parent)

    override fun onBindViewHolder(holder: AlbumCoverViewHolder, position: Int) {
        val cellPx = (recyclerView?.layoutManager as? AlbumGridLayoutManager)?.cellSize() ?: 0
        holder.bind(getItem(position), listener, cellPx)
    }
}
