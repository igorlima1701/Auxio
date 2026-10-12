/*
 * Copyright (c) 2021 Auxio Project
 * AlbumListFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.doOnLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeListBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.home.filter.AlbumFilter
import org.oxycblt.auxio.home.filter.AlbumFilterDialog
import org.oxycblt.auxio.image.coil.GridThumbWarmer
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.recycler.FastScrollRecyclerView
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMsPopup
import org.oxycblt.auxio.rating.AlbumRatings
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * A [ListFragment] that shows a list of [Album]s.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class AlbumListFragment :
    ListFragment<Album, FragmentHomeListBinding>(),
    FastScrollRecyclerView.Listener,
    FastScrollRecyclerView.PopupProvider {
    private val homeModel: HomeViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    private val albumAdapter = AlbumCoverAdapter(this)
    @Inject lateinit var gridThumbWarmer: GridThumbWarmer

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.homeRecycler.apply {
            id = R.id.home_album_recycler
            // Fork pessoal: grade só de capas, com colunas adaptadas à largura da tela.
            layoutManager =
                AlbumGridLayoutManager(
                    context,
                    resources.getDimensionPixelSize(R.dimen.size_album_grid_min_cell),
                )
            val halfGap = resources.getDimensionPixelSize(R.dimen.spacing_album_grid_half_gap)
            setPaddingRelative(halfGap, paddingTop, halfGap, paddingBottom)
            // Mais células prontas para reciclar, para a rolagem rápida não precisar inflar views.
            recycledViewPool.setMaxRecycledViews(0, 48)
            setItemViewCacheSize(12)
            itemAnimator = null
            adapter = albumAdapter
            popupProvider = this@AlbumListFragment
            listener = this@AlbumListFragment
        }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_album_48)
            contentDescription = getString(R.string.lbl_albums)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_albums)

        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }

        collectImmediately(homeModel.albumList, ::updateAlbums)
        binding.homeFilterChip.apply {
            setOnClickListener {
                AlbumFilterDialog().show(childFragmentManager, AlbumFilterDialog.TAG)
            }
            setOnCloseIconClickListener { homeModel.clearAlbumFilter() }
        }
        collectImmediately(homeModel.albumFilter, ::updateFilter)
        collectImmediately(homeModel.empty, musicModel.indexingState, ::updateNoMusicIndicator)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
    }

    override fun onDestroyBinding(binding: FragmentHomeListBinding) {
        super.onDestroyBinding(binding)
        binding.homeRecycler.apply {
            adapter = null
            popupProvider = null
            listener = null
        }
    }

    override fun getPopupData(pos: Int): FastScrollRecyclerView.PopupProvider.PopupData? {
        val album = homeModel.albumList.value.getOrNull(pos) ?: return null
        // Change how we display the popup depending on the current sort mode.
        return when (homeModel.albumSort.mode) {
            // By Name -> Use Name
            is Sort.Mode.ByName ->
                FastScrollRecyclerView.PopupProvider.PopupData(album.name.thumb() ?: "?")

            // By Artist -> Use name of first artist
            is Sort.Mode.ByArtist ->
                FastScrollRecyclerView.PopupProvider.PopupData(album.artists[0].name.thumb() ?: "?")

            // Date -> Use year of the range minimum
            is Sort.Mode.ByDate -> {
                val year = album.dates?.min?.year ?: return null
                FastScrollRecyclerView.PopupProvider.PopupData(getString(R.string.fmt_number, year))
            }

            // Duration -> Use compact bucket duration
            is Sort.Mode.ByDuration ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    album.durationMs.formatDurationMsPopup()
                )

            // Count -> Use song count
            is Sort.Mode.ByCount ->
                FastScrollRecyclerView.PopupProvider.PopupData(album.songs.size.toString())

            // Last added -> Use year
            is Sort.Mode.ByDateAdded -> {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = album.addedMs
                FastScrollRecyclerView.PopupProvider.PopupData(
                    getString(R.string.fmt_number, calendar.get(Calendar.YEAR))
                )
            }

            // Fork pessoal: nota
            is Sort.Mode.ByRating ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    AlbumRatings.get(album)?.let { if (it == 0) "0" else "$it★" } ?: "–"
                )

            // Unsupported sort, error gracefully
            else -> null
        }
    }

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    override fun onRealClick(item: Album) {
        detailModel.showAlbum(item)
    }

    override fun onOpenMenu(item: Album) {
        listModel.openMenu(R.menu.album, item)
    }

    private fun updateAlbums(albums: List<Album>) {
        albumAdapter.update(albums, homeModel.albumInstructions.consume())
        // Gera em segundo plano as miniaturas que faltam, no tamanho exato das células.
        val recycler = requireBinding().homeRecycler
        recycler.doOnLayout {
            val cell = (recycler.layoutManager as? AlbumGridLayoutManager)?.cellSize() ?: 0
            val halfGap = recycler.resources.getDimensionPixelSize(R.dimen.spacing_album_grid_half_gap)
            gridThumbWarmer.warm(albums, cell - 2 * halfGap)
        }
    }

    /** Fork pessoal: mostra o resumo do filtro ativo e abre espaço para ele no topo da grade. */
    private fun updateFilter(filter: AlbumFilter) {
        val binding = requireBinding()
        val chip = binding.homeFilterChip
        if (filter.isEmpty) {
            chip.isVisible = false
            binding.homeRecycler.updatePadding(top = 0)
            return
        }
        val parts = mutableListOf<String>()
        if (filter.ratings.isNotEmpty()) {
            parts +=
                filter.ratings.sorted().joinToString(", ") {
                    when (it) {
                        AlbumFilter.NO_RATING -> getString(R.string.lbl_no_rating)
                        0 -> getString(R.string.lbl_rating_zero)
                        else -> getString(R.string.fmt_rating_stars, it)
                    }
                }
        }
        if (filter.years.isNotEmpty()) {
            parts += filter.years.sortedDescending().joinToString(", ")
        }
        chip.text = parts.joinToString(" · ")
        chip.isVisible = true
        binding.homeRecycler.updatePadding(
            top = resources.getDimensionPixelSize(R.dimen.size_album_filter_space)
        )
        binding.homeRecycler.scrollToPosition(0)
    }

    private fun updateNoMusicIndicator(empty: Boolean, indexingState: IndexingState?) {
        val binding = requireBinding()
        binding.homeRecycler.isInvisible = empty
        binding.homeNoMusic.isInvisible = !empty
        binding.homeNoMusicAction.isVisible =
            indexingState == null || (empty && indexingState is IndexingState.Completed)
    }

    private fun updateSelection(selection: List<Music>) {
        albumAdapter.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        // Only highlight the album if it is currently playing, and if the currently
        // playing song is also contained within.
        val album = (parent as? Album)?.takeIf { song?.album == it }
        albumAdapter.setPlaying(album, isPlaying)
    }
}
