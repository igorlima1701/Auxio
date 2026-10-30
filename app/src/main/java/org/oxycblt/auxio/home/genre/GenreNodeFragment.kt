/*
 * Copyright (c) 2026 Auxio Project
 * GenreNodeFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
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
import org.oxycblt.auxio.home.list.GenreListFragment
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name

/**
 * Fork pessoal: um gênero com subgêneros (ex.: "Rock"), mostrando os álbuns ou as músicas dele e de
 * todos os subgêneros. Embaixo de cada álbum, os gêneros que ele tem.
 */
@AndroidEntryPoint
class GenreNodeFragment : ViewBindingFragment<FragmentAutoPlaylistBinding>() {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var listSettings: ListSettings

    private class Row(val music: Music, val subtitle: String, val genres: CharSequence)

    private var node: GenreTree.Node? = null
    private var rows: List<Row> = emptyList()
    private val adapter = RowAdapter()

    private val key: String
        get() = requireArguments().getString(ARG_KEY).orEmpty()

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentAutoPlaylistBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentAutoPlaylistBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.autoToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.autoName.isVisible = false
        binding.autoPeriodsScroll.isVisible = false
        binding.autoRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.autoRecycler.adapter = adapter
        addChoice(binding.autoMode, getString(R.string.lbl_albums), showAlbums) {
            showAlbums = true
            render(toTop = true)
        }
        addChoice(binding.autoMode, getString(R.string.lbl_songs), !showAlbums) {
            showAlbums = false
            render(toTop = true)
        }
        binding.autoPlay.setOnClickListener { play(false, 0) }
        binding.autoShuffle.setOnClickListener { play(true, 0) }

        val restoring = node != null
        if (restoring) render(toTop = false) else load()
    }

    override fun onDestroyBinding(binding: FragmentAutoPlaylistBinding) {
        super.onDestroyBinding(binding)
        binding.autoRecycler.adapter = null
    }

    private fun load() {
        val context = requireContext().applicationContext
        val library = musicRepository.library
        viewLifecycleOwner.lifecycleScope.launch {
            val found =
                withContext(Dispatchers.Default) {
                    val roots =
                        GenreListFragment.lastRoots.ifEmpty {
                            library?.let { GenreTree.build(context, it.genres) }.orEmpty()
                        }
                    GenreTree.find(roots, key)
                }
            node = found
            render(toTop = true)
        }
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

    /** Chaves deste gênero e de todos os subgêneros. */
    private var branch: Set<String> = emptySet()

    private fun collectBranch(node: GenreTree.Node): Set<String> {
        val out = HashSet<String>()
        val stack = ArrayDeque(listOf(node))
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (out.add(n.key)) stack.addAll(n.children)
        }
        return out
    }

    /**
     * Gêneros de [songs] (sem repetir): primeiro, na cor de destaque, os que fazem parte deste ramo
     * (o motivo de o álbum estar aqui); depois os outros.
     */
    private fun genresOf(songs: Collection<Song>, accent: Int): CharSequence {
        val names =
            songs
                .flatMap { it.genres }
                .distinct()
                .flatMap { (it.name as? Name.Known)?.raw?.let(GenreTree::split).orEmpty() }
                .distinctBy { GenreTree.key(it) }
        val (inside, outside) = names.partition { GenreTree.key(it) in branch }
        val out = android.text.SpannableStringBuilder()
        for ((i, name) in (inside + outside).withIndex()) {
            if (i > 0) out.append(" · ")
            val start = out.length
            out.append(name)
            if (i < inside.size) {
                out.setSpan(
                    android.text.style.ForegroundColorSpan(accent),
                    start,
                    out.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return out
    }

    private fun render(toTop: Boolean) {
        val binding = binding ?: return
        val context = requireContext()
        val current = node
        binding.autoToolbar.title = current?.name ?: ""
        branch = current?.let(::collectBranch).orEmpty()
        val accent =
            com.google.android.material.color.MaterialColors.getColor(
                binding.root,
                androidx.appcompat.R.attr.colorPrimary,
            )
        rows =
            when {
                current == null -> emptyList()
                showAlbums ->
                    listSettings.albumSort.albums(current.albums).map { album ->
                        val artist = album.artists.resolveNames(context)
                        Row(album, artist, genresOf(album.songs, accent))
                    }
                else ->
                    listSettings.songSort.songs(current.songs).map { song ->
                        Row(song, song.artists.resolveNames(context), genresOf(listOf(song), accent))
                    }
            }
        adapter.notifyDataSetChanged()
        binding.autoCount.text =
            if (showAlbums)
                resources.getQuantityString(R.plurals.fmt_album_count, rows.size, rows.size)
            else resources.getQuantityString(R.plurals.fmt_song_count, rows.size, rows.size)
        binding.autoEmpty.text = getString(R.string.lng_auto_empty)
        binding.autoEmpty.isVisible = rows.isEmpty()
        binding.autoRecycler.isVisible = rows.isNotEmpty()
        if (toTop) binding.autoRecycler.scrollToPosition(0)
    }

    /** Músicas na ordem mostrada (álbuns: as faixas de cada um, na ordem do disco). */
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
        val name = node?.name ?: return
        if (!playbackModel.playInNewQueue(songs, name, shuffle, start)) {
            Toast.makeText(requireContext(), R.string.err_play_filter, Toast.LENGTH_LONG).show()
        }
    }

    private fun onRowClick(position: Int) {
        val row = rows.getOrNull(position) ?: return
        when (val music = row.music) {
            is Song -> play(false, position)
            is Album ->
                findNavController()
                    .navigate(R.id.album_detail_fragment, bundleOf("albumUid" to music.uid))
            else -> {}
        }
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
            b.autoSubtitle.text = row.subtitle
            b.autoGenres.text = row.genres
            b.autoGenres.isVisible = row.genres.isNotEmpty()
            b.root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRowClick(pos)
            }
        }
    }

    private class RowHolder(val binding: ItemAutoRowBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        const val ARG_KEY = "genreKey"

        /** Última escolha entre álbuns e músicas (vale enquanto o app está aberto). */
        private var showAlbums = true
    }
}
