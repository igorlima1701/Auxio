/*
 * Copyright (c) 2026 Auxio Project
 * MenuNav.kt is part of Auxio.
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

package org.oxycblt.auxio.list.menu

import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.oxycblt.auxio.R
import timber.log.Timber as L

/**
 * Fork pessoal: abre os menus (da música, do álbum…) a partir das telas do fork (lista de um
 * gênero, playlists automáticas). Sem isso, um menu pedido com uma dessas telas aberta (por exemplo
 * o "..." do player) ficava pendente e nenhum outro menu abria depois.
 */
object MenuNav {
    fun open(fragment: Fragment, menu: Menu?) {
        if (menu == null) return
        val dest =
            when (menu) {
                is Menu.ForSong -> R.id.song_menu_dialog
                is Menu.ForAlbum -> R.id.album_menu_dialog
                is Menu.ForArtist -> R.id.artist_menu_dialog
                is Menu.ForGenre -> R.id.genre_menu_dialog
                is Menu.ForPlaylist -> R.id.playlist_menu_dialog
                is Menu.ForSelection -> R.id.selection_menu_dialog
            }
        try {
            fragment.findNavController().navigate(dest, bundleOf("parcel" to menu.parcel))
        } catch (e: Exception) {
            L.e(e, "Não consegui abrir o menu")
        }
    }
}
