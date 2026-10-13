/*
 * Copyright (c) 2022 Auxio Project
 * SelectionFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.list

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import androidx.viewbinding.ViewBinding
import org.oxycblt.auxio.R
import org.oxycblt.musikr.Music
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.AuxioToolbar
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.showToast

/**
 * A subset of ListFragment that implements aspects of the selection UI.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
abstract class SelectionFragment<VB : ViewBinding> :
    ViewBindingFragment<VB>(), Toolbar.OnMenuItemClickListener {
    protected abstract val listModel: ListViewModel
    protected abstract val musicModel: MusicViewModel
    protected abstract val playbackModel: PlaybackViewModel

    open fun getSelectionToolbar(binding: VB): AuxioToolbar? = null

    /** Fork pessoal: os itens da lista mostrada, na ordem da tela (para tudo/inverter/intervalo). */
    protected open fun selectableItems(): List<Music> = emptyList()

    /** Itens do mesmo tipo dos já selecionados (música com música, álbum com álbum…). */
    private fun itemsLikeSelection(): List<Music> {
        val items = selectableItems()
        val first = listModel.selected.value.firstOrNull() ?: return items
        fun kind(m: Music) =
            when (m) {
                is org.oxycblt.musikr.Song -> 0
                is org.oxycblt.musikr.Album -> 1
                is org.oxycblt.musikr.Artist -> 2
                is org.oxycblt.musikr.Genre -> 3
                is org.oxycblt.musikr.Playlist -> 4
                else -> 5
            }
        return items.filter { kind(it) == kind(first) }
    }

    override fun onBindingCreated(binding: VB, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        getSelectionToolbar(binding)?.apply {
            // Add cancel and menu item listeners to manage what occurs with the selection.
            setNavigationOnClickListener { listModel.dropSelection() }
            setOnMenuItemClickListener(this@SelectionFragment)
            setOnOverflowMenuClick {
                listModel.openMenu(R.menu.selection, listModel.peekSelection())
            }
        }
    }

    override fun onDestroyBinding(binding: VB) {
        super.onDestroyBinding(binding)
        getSelectionToolbar(binding)?.setOnMenuItemClickListener(null)
    }

    override fun onMenuItemClick(item: MenuItem) =
        when (item.itemId) {
            R.id.action_selection_play_next -> {
                playbackModel.playNext(listModel.takeSelection())
                requireContext().showToast(R.string.lng_play_next)
                true
            }
            R.id.action_selection_playlist_add -> {
                musicModel.addToPlaylist(listModel.takeSelection())
                true
            }
            R.id.action_selection_all -> {
                listModel.selectAll(itemsLikeSelection())
                true
            }
            R.id.action_selection_invert -> {
                listModel.invertSelection(itemsLikeSelection())
                true
            }
            R.id.action_selection_range -> {
                listModel.selectRange(itemsLikeSelection())
                true
            }
            else -> false
        }

    // TODO: Re-add the automatic selection handling
}
