/*
 * Copyright (c) 2026 Auxio Project
 * AutoPlaylistFragment.kt is part of Auxio.
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

package org.oxycblt.auxio.home.auto

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentAutoPlaylistBinding
import org.oxycblt.auxio.databinding.ItemAutoRowBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: uma playlist automática (Tocadas recentemente, Mais tocadas, Não tocadas), com a
 * escolha entre músicas e álbuns e, em "Não tocadas", o período.
 */
@AndroidEntryPoint
class AutoPlaylistFragment : ViewBindingFragment<FragmentAutoPlaylistBinding>() {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val listModel: org.oxycblt.auxio.list.ListViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var listSettings: ListSettings

    private val kind: AutoPlaylists.Kind
        get() = AutoPlaylists.Kind.entries[requireArguments().getInt(ARG_KIND)]

    /**
     * As linhas ficam na tela (fragmento) enquanto ela estiver na pilha: voltando de um álbum, a
     * lista aparece na hora, no mesmo ponto da rolagem.
     */
    private var rows: List<AutoPlaylists.Row> = emptyList()
    private val adapter = RowAdapter()

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentAutoPlaylistBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentAutoPlaylistBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.autoToolbar.title = getString(titleOf(kind))
        binding.autoToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        // Fork pessoal: menus pedidos com esta tela aberta (ex.: "..." do player).
        collect(listModel.menu.flow) { org.oxycblt.auxio.list.menu.MenuNav.open(this, it) }
        binding.autoName.isVisible = false
        binding.autoRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.autoRecycler.adapter = adapter
        val restoring = rows.isNotEmpty()
        if (restoring) showRows(binding, rows)

        // Músicas / Álbuns
        addChoice(binding.autoMode, getString(R.string.lbl_songs), !showAlbums) {
            showAlbums = false
            reload(toTop = true)
        }
        addChoice(binding.autoMode, getString(R.string.lbl_albums), showAlbums) {
            showAlbums = true
            reload(toTop = true)
        }

        // Período ("Não tocadas" e "Adicionadas recentemente")
        val added = kind == AutoPlaylists.Kind.ADDED
        binding.autoPeriodsScroll.isVisible = added || kind == AutoPlaylists.Kind.NOT_PLAYED
        if (kind == AutoPlaylists.Kind.NOT_PLAYED) {
            for (days in AutoPlaylists.PERIODS) {
                val label =
                    if (days == null) getString(R.string.lbl_auto_never)
                    else getString(R.string.fmt_auto_days, days)
                addChoice(binding.autoPeriods, label, days == period) {
                    period = days
                    reload(toTop = true)
                }
            }
        } else if (added) {
            for (days in AutoPlaylists.ADDED_PERIODS) {
                val label =
                    if (days == null) getString(R.string.lbl_auto_added_all)
                    else getString(R.string.fmt_auto_added_days, days)
                addChoice(binding.autoPeriods, label, days == addedPeriod) {
                    addedPeriod = days
                    reload(toTop = true)
                }
            }
        }

        binding.autoPlay.setOnClickListener { play(false, 0) }
        binding.autoShuffle.setOnClickListener { play(true, 0) }
        // Voltando de um álbum: só atualiza (sem mexer na rolagem). Abrindo: carrega do zero.
        reload(toTop = !restoring)
    }

    override fun onDestroyBinding(binding: FragmentAutoPlaylistBinding) {
        super.onDestroyBinding(binding)
        binding.autoRecycler.adapter = null
    }

    private fun addChoice(group: ChipGroup, label: String, checked: Boolean, onPick: () -> Unit) {
        val chip =
            (LayoutInflater.from(group.context).inflate(R.layout.item_filter_chip, group, false)
                    as Chip)
                .apply {
                    text = label
                    isCheckable = true
                    isChecked = checked
                    setOnClickListener { if (isChecked) onPick() }
                }
        group.addView(chip)
    }

    private fun reload(toTop: Boolean) {
        val library = musicRepository.library
        val context = requireContext()
        val albums = showAlbums
        val currentKind = kind
        val days = if (currentKind == AutoPlaylists.Kind.ADDED) addedPeriod else period
        val settings = listSettings
        viewLifecycleOwner.lifecycleScope.launch {
            val result =
                if (library == null) emptyList()
                else
                    withContext(Dispatchers.Default) {
                        if (albums) AutoPlaylists.albums(context, library, settings, currentKind, days)
                        else AutoPlaylists.songs(context, library, settings, currentKind, days)
                    }
            val binding = this@AutoPlaylistFragment.binding ?: return@launch
            if (!toTop && result == rows) return@launch
            showRows(binding, result)
            if (toTop) binding.autoRecycler.scrollToPosition(0)
            binding.autoEmpty.text =
                getString(
                    if (library == null) R.string.lng_auto_loading
                    else if (!org.oxycblt.auxio.playback.PlayHistory.isLoaded) R.string.lng_auto_loading
                    else R.string.lng_auto_empty
                )
        }
    }

    private fun showRows(binding: FragmentAutoPlaylistBinding, result: List<AutoPlaylists.Row>) {
        val albums = showAlbums
        rows = result
        adapter.notifyDataSetChanged()
        run {
            binding.autoCount.text =
                if (albums) resources.getQuantityString(R.plurals.fmt_album_count, result.size, result.size)
                else resources.getQuantityString(R.plurals.fmt_song_count, result.size, result.size)
            binding.autoEmpty.isVisible = result.isEmpty()
            binding.autoRecycler.isVisible = result.isNotEmpty()
        }
    }

    /** Músicas da lista na ordem mostrada (álbuns: faixas de cada um, na ordem do disco). */
    private fun songsOfRows(): List<Song> =
        rows.flatMap { row ->
            when (val music = row.music) {
                is Song -> listOf(music)
                is Album -> listSettings.albumSongSort.songs(music.songs)
                else -> emptyList()
            }
        }

    private fun play(shuffle: Boolean, start: Int) {
        val songs = songsOfRows()
        if (songs.isEmpty()) return
        val name = getString(titleOf(kind))
        if (!playbackModel.playInNewQueue(songs, name, shuffle, start)) {
            Toast.makeText(requireContext(), R.string.err_play_filter, Toast.LENGTH_LONG).show()
        }
    }

    private fun onRowClick(position: Int) {
        val row = rows.getOrNull(position) ?: return
        when (val music = row.music) {
            is Song -> play(false, position)
            is Album ->
                // A tela do álbum entra por cima desta; voltar traz a lista de novo.
                findNavController()
                    .navigate(R.id.album_detail_fragment, bundleOf("albumUid" to music.uid))
            else -> {}
        }
    }

    private fun subtitle(row: AutoPlaylists.Row): String {
        val context = requireContext()
        val artist =
            when (val music = row.music) {
                is Song -> music.artists.resolveNames(context)
                is Album -> music.artists.resolveNames(context)
                else -> ""
            }
        val plays =
            if (row.count == 0) getString(R.string.lng_auto_never_played)
            else
                getString(
                    R.string.fmt_auto_plays,
                    row.count,
                    DateUtils.getRelativeTimeSpanString(
                        row.lastMs ?: 0L,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ),
                )
        // Fork pessoal: álbum em "Não tocadas" mostra quanto já foi ouvido no período.
        if (row.music is Album && kind == AutoPlaylists.Kind.NOT_PLAYED) {
            return "$artist · " + getString(R.string.fmt_auto_tracks_played, row.played, row.total)
        }
        return "$artist · $plays"
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(ItemAutoRowBinding.inflate(parent.context.inflater, parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val row = rows[position]
            val b = holder.binding
            when (val music = row.music) {
                is Song -> {
                    b.autoCover.bind(music)
                    b.autoTitle.text = music.name.resolve(b.root.context)
                }
                is Album -> {
                    b.autoCover.bind(music)
                    b.autoTitle.text = music.name.resolve(b.root.context)
                }
                else -> {}
            }
            b.autoSubtitle.text = subtitle(row)
            b.root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRowClick(pos)
            }
        }
    }

    private class RowHolder(val binding: ItemAutoRowBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        const val ARG_KIND = "kind"

        // Últimas escolhas (valem enquanto o app está aberto).
        private var showAlbums = false
        private var period: Int? = null
        private var addedPeriod: Int? = 30

        fun titleOf(kind: AutoPlaylists.Kind) =
            when (kind) {
                AutoPlaylists.Kind.ALL -> R.string.lbl_auto_all
                AutoPlaylists.Kind.ADDED -> R.string.lbl_auto_added
                AutoPlaylists.Kind.RECENT -> R.string.lbl_auto_recent
                AutoPlaylists.Kind.MOST -> R.string.lbl_auto_most
                AutoPlaylists.Kind.NOT_PLAYED -> R.string.lbl_auto_not_played
            }

        fun args(kind: AutoPlaylists.Kind) = bundleOf(ARG_KIND to kind.ordinal)
    }
}
