/*
 * Copyright (c) 2026 Auxio Project
 * AutoPlaylistDialog.kt is part of Auxio.
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
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.bottomsheet.BackportBottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogAutoPlaylistBinding
import org.oxycblt.auxio.databinding.ItemAutoRowBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: uma playlist automática (Tocadas recentemente, Mais tocadas, Não tocadas), com a
 * escolha entre músicas e álbuns e, em "Não tocadas", o período.
 */
@AndroidEntryPoint
class AutoPlaylistDialog : ViewBindingBottomSheetDialogFragment<DialogAutoPlaylistBinding>() {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var listSettings: ListSettings

    private val kind: AutoPlaylists.Kind
        get() = AutoPlaylists.Kind.entries[requireArguments().getInt(ARG_KIND)]

    private var rows: List<AutoPlaylists.Row> = emptyList()
    private val adapter = RowAdapter()

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogAutoPlaylistBinding.inflate(inflater)

    override fun onStart() {
        super.onStart()
        (dialog as? BackportBottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BackportBottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onBindingCreated(binding: DialogAutoPlaylistBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.root.minimumHeight = (resources.displayMetrics.heightPixels * 0.85).toInt()
        binding.autoName.text = getString(titleOf(kind))
        binding.autoRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.autoRecycler.adapter = adapter

        // Músicas / Álbuns
        addChoice(binding.autoMode, getString(R.string.lbl_songs), !showAlbums) {
            showAlbums = false
            reload()
        }
        addChoice(binding.autoMode, getString(R.string.lbl_albums), showAlbums) {
            showAlbums = true
            reload()
        }

        // Período (só em "Não tocadas")
        binding.autoPeriodsScroll.isVisible = kind == AutoPlaylists.Kind.NOT_PLAYED
        if (kind == AutoPlaylists.Kind.NOT_PLAYED) {
            for (days in AutoPlaylists.PERIODS) {
                val label =
                    if (days == null) getString(R.string.lbl_auto_never)
                    else getString(R.string.fmt_auto_days, days)
                addChoice(binding.autoPeriods, label, days == period) {
                    period = days
                    reload()
                }
            }
        }

        binding.autoPlay.setOnClickListener { play(false, 0) }
        binding.autoShuffle.setOnClickListener { play(true, 0) }
        reload()
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

    private fun reload() {
        val library = musicRepository.library
        val context = requireContext()
        val albums = showAlbums
        val currentKind = kind
        val days = period
        viewLifecycleOwner.lifecycleScope.launch {
            val result =
                if (library == null) emptyList()
                else
                    withContext(Dispatchers.Default) {
                        if (albums) AutoPlaylists.albums(context, library, currentKind, days)
                        else AutoPlaylists.songs(context, library, currentKind, days)
                    }
            val binding = this@AutoPlaylistDialog.binding ?: return@launch
            rows = result
            adapter.notifyDataSetChanged()
            binding.autoRecycler.scrollToPosition(0)
            binding.autoCount.text =
                if (albums) resources.getQuantityString(R.plurals.fmt_album_count, result.size, result.size)
                else resources.getQuantityString(R.plurals.fmt_song_count, result.size, result.size)
            binding.autoEmpty.isVisible = result.isEmpty()
            binding.autoRecycler.isVisible = result.isNotEmpty()
            binding.autoEmpty.text =
                getString(
                    if (library == null) R.string.lng_auto_loading
                    else if (!org.oxycblt.auxio.playback.PlayHistory.isLoaded) R.string.lng_auto_loading
                    else R.string.lng_auto_empty
                )
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
        if (playbackModel.playInNewQueue(songs, name, shuffle, start)) {
            dismiss()
        } else {
            Toast.makeText(requireContext(), R.string.err_play_filter, Toast.LENGTH_LONG).show()
        }
    }

    private fun onRowClick(position: Int) {
        val row = rows.getOrNull(position) ?: return
        when (val music = row.music) {
            is Song -> play(false, position)
            is Album -> {
                dismiss()
                detailModel.showAlbum(music)
            }
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
        private const val TAG = "auxio.fork.AUTO_PLAYLIST"
        private const val ARG_KIND = "kind"

        // Últimas escolhas (valem enquanto o app está aberto).
        private var showAlbums = false
        private var period: Int? = null

        fun titleOf(kind: AutoPlaylists.Kind) =
            when (kind) {
                AutoPlaylists.Kind.RECENT -> R.string.lbl_auto_recent
                AutoPlaylists.Kind.MOST -> R.string.lbl_auto_most
                AutoPlaylists.Kind.NOT_PLAYED -> R.string.lbl_auto_not_played
            }

        fun show(manager: FragmentManager, kind: AutoPlaylists.Kind) {
            if (manager.findFragmentByTag(TAG) != null) return
            AutoPlaylistDialog()
                .apply { arguments = bundleOf(ARG_KIND to kind.ordinal) }
                .show(manager, TAG)
        }
    }
}
