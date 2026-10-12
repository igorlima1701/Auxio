/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackBarFragment.kt is part of Auxio.
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

package org.oxycblt.auxio.playback

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.PopupMenu
import androidx.core.view.isInvisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.R as MR
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackBarBinding
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: barra de abas no rodapé, no lugar do mini player.
 *
 * Fila e Player abrem o player; Álbuns, Artistas e Playlists trocam a aba da tela inicial; Busca
 * abre a busca geral; Mais tem Músicas, Gêneros, Configurações e Sobre.
 */
@AndroidEntryPoint
class PlaybackBarFragment : ViewBindingFragment<FragmentPlaybackBarBinding>() {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackBarBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaybackBarBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.navQueue.setOnClickListener {
            if (playbackModel.song.value != null) playbackModel.openQueue()
        }
        binding.navPlayer.setOnClickListener {
            if (playbackModel.song.value != null) playbackModel.openPlayback()
        }
        binding.navAlbums.setOnClickListener { homeModel.showTab(MusicType.ALBUMS) }
        binding.navArtists.setOnClickListener { homeModel.showTab(MusicType.ARTISTS) }
        binding.navPlaylists.setOnClickListener { homeModel.showTab(MusicType.PLAYLISTS) }
        binding.navSearch.setOnClickListener { homeModel.openSearch() }
        binding.navMore.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                inflate(R.menu.nav_more)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.nav_more_songs -> homeModel.showTab(MusicType.SONGS)
                        R.id.nav_more_genres -> homeModel.showTab(MusicType.GENRES)
                        R.id.nav_more_settings -> homeModel.showSettings()
                        R.id.nav_more_about -> homeModel.showAbout()
                    }
                    true
                }
                show()
            }
        }

        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(homeModel.currentTabType, homeModel.mainPage, ::updateTab)
    }

    private fun updateSong(song: Song?) {
        val binding = requireBinding()
        val enabled = song != null
        for (button in listOf(binding.navQueue, binding.navPlayer)) {
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.38f
        }
    }

    private fun updateTab(tab: MusicType, page: HomeViewModel.MainPage) {
        val binding = requireBinding()
        val context = requireContext()
        val active = context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
        val inactive = context.getAttrColorCompat(MR.attr.colorOnSurfaceVariant)
        val selected =
            when (page) {
                HomeViewModel.MainPage.QUEUE -> binding.navQueue
                HomeViewModel.MainPage.PLAYBACK -> binding.navPlayer
                HomeViewModel.MainPage.HOME ->
                    when (tab) {
                        MusicType.ALBUMS -> binding.navAlbums
                        MusicType.ARTISTS -> binding.navArtists
                        MusicType.PLAYLISTS -> binding.navPlaylists
                        else -> binding.navMore
                    }
            }
        val marks =
            mapOf(
                binding.navQueue to binding.navMarkQueue,
                binding.navPlayer to binding.navMarkPlayer,
                binding.navAlbums to binding.navMarkAlbums,
                binding.navArtists to binding.navMarkArtists,
                binding.navPlaylists to binding.navMarkPlaylists,
                binding.navSearch to binding.navMarkSearch,
                binding.navMore to binding.navMarkMore,
            )
        for ((button, mark) in marks) {
            mark.isInvisible = button != selected
        }
        for (button in
            listOf<ImageButton>(
                binding.navQueue,
                binding.navPlayer,
                binding.navAlbums,
                binding.navArtists,
                binding.navPlaylists,
                binding.navSearch,
                binding.navMore,
            )) {
            button.imageTintList = if (button == selected) active else inactive
        }
    }
}
