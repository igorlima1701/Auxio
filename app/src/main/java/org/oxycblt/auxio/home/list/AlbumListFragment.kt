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
    @Inject lateinit var gridThumbStore: org.oxycblt.auxio.image.coil.GridThumbStore
    /** Fork pessoal: foto da grade mostrada enquanto a biblioteca carrega. */
    private var snapshotView: androidx.recyclerview.widget.RecyclerView? = null
    /** Álbum tocado na foto, para abrir quando a biblioteca carregar. */
    private var pendingSnapshotTap: String? = null

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

        // Fork pessoal: abertura do app -> mostra na hora a última grade, enquanto carrega.
        if (
            homeModel.albumList.value.isEmpty() &&
                homeModel.albumFilter.value.isEmpty &&
                musicModel.indexingState.value !is IndexingState.Completed
        ) {
            // Fork pessoal: a foto da grade deu lugar à tela de carregamento (StartupGate); fica
            // desligada.
            if (USE_SNAPSHOT) {
                val entries = GridSnapshot.load(requireContext())
                if (entries.isNotEmpty()) showSnapshot(binding, entries)
            }
        }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_album_48)
            contentDescription = getString(R.string.lbl_albums)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_albums)

        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }

        collectImmediately(homeModel.albumList, ::updateAlbums)
        collectImmediately(homeModel.revealAlbumEvent.flow, ::revealAlbum)
        binding.homeFilterChip.apply {
            setOnClickListener {
                AlbumFilterDialog().show(childFragmentManager, AlbumFilterDialog.TAG)
            }
            setOnCloseIconClickListener { homeModel.clearAlbumFilter() }
        }
        binding.homeFilterPlay.setOnClickListener { showPlayFilterMenu(it) }
        collectImmediately(homeModel.albumFilter, ::updateFilter)
        collectImmediately(homeModel.empty, musicModel.indexingState, ::updateNoMusicIndicator)
        collectImmediately(musicModel.indexingState) { state ->
            // Biblioteca pronta mas sem álbuns (ou sem mudança na lista): tira a foto mesmo assim.
            if (state is IndexingState.Completed && snapshotView != null) {
                requireBinding().root.postDelayed(
                    {
                        if (!albumsWaitingIdle) hideSnapshot(homeModel.albumList.value)
                    },
                    1000,
                )
            }
        }
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
    }

    /**
     * Fork pessoal: rola o grid até [album] (o que está tocando), se ele não estiver à vista. Sem
     * animação; se já estiver na tela, o grid não se mexe.
     */
    private fun revealAlbum(album: Album?) {
        if (album == null) return
        homeModel.revealAlbumEvent.consume()
        val idx = homeModel.albumList.value.indexOf(album)
        if (idx < 0) return
        val recycler = requireBinding().homeRecycler
        recycler.post {
            val lm =
                recycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                    ?: return@post
            val first = lm.findFirstCompletelyVisibleItemPosition()
            val last = lm.findLastCompletelyVisibleItemPosition()
            if (first == androidx.recyclerview.widget.RecyclerView.NO_POSITION || idx !in first..last) {
                lm.scrollToPositionWithOffset(idx, recycler.height / 3)
            }
        }
    }

    private fun showSnapshot(binding: FragmentHomeListBinding, entries: List<GridSnapshot.Entry>) {
        val real = binding.homeRecycler
        val overlay = androidx.recyclerview.widget.RecyclerView(requireContext())
        val lm =
            AlbumGridLayoutManager(
                requireContext(),
                resources.getDimensionPixelSize(R.dimen.size_album_grid_min_cell),
            )
        overlay.layoutManager = lm
        overlay.itemAnimator = null
        // Como na grade de verdade: células prontas para reciclar, sem inflar no meio da rolagem.
        overlay.setHasFixedSize(true)
        overlay.recycledViewPool.setMaxRecycledViews(0, 48)
        overlay.setItemViewCacheSize(12)
        overlay.clipToPadding = false
        overlay.setBackgroundColor(
            com.google.android.material.color.MaterialColors.getColor(
                real,
                android.R.attr.colorBackground,
            )
        )
        overlay.setPadding(real.paddingLeft, real.paddingTop, real.paddingRight, real.paddingBottom)
        // Acompanha as margens da grade de verdade (barras do sistema etc.).
        real.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (
                overlay.paddingLeft != v.paddingLeft ||
                    overlay.paddingTop != v.paddingTop ||
                    overlay.paddingRight != v.paddingRight ||
                    overlay.paddingBottom != v.paddingBottom
            ) {
                overlay.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
            }
        }
        overlay.adapter =
            GridSnapshotAdapter(entries, { lm.cellSize() }) { uid -> pendingSnapshotTap = uid }
        overlay.addOnScrollListener(
            object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    newState: Int,
                ) {
                    if (
                        newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE &&
                            albumsWaitingIdle &&
                            view != null
                    ) {
                        updateAlbums(homeModel.albumList.value)
                    }
                }
            }
        )
        (binding.root as android.view.ViewGroup).addView(
            overlay,
            1,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        snapshotView = overlay
    }

    /** Troca a foto pela grade de verdade, na mesma posição, e abre o álbum tocado na espera. */
    private fun hideSnapshot(albums: List<Album>) {
        val overlay = snapshotView ?: return
        snapshotView = null
        val binding = view?.let { requireBinding() }
        if (binding != null && albums.isNotEmpty()) {
            val lm = overlay.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
            val first = lm.findFirstVisibleItemPosition()
            if (first > 0) {
                val offset = (lm.findViewByPosition(first)?.top ?: overlay.paddingTop) - overlay.paddingTop
                val real = binding.homeRecycler
                real.post {
                    (real.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                        ?.scrollToPositionWithOffset(first.coerceAtMost(albums.lastIndex), offset)
                }
            }
            pendingSnapshotTap?.let { uid ->
                albums.find { it.uid.toString() == uid }?.let { detailModel.showAlbum(it) }
            }
        }
        pendingSnapshotTap = null
        if (binding != null) {
            updateNoMusicIndicator(homeModel.empty.value, musicModel.indexingState.value)
        }
        // Um instante para as capas da grade de verdade aparecerem por baixo.
        overlay.postDelayed(
            {
                (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
                overlay.adapter = null
            },
            300,
        )
    }

    override fun onDestroyBinding(binding: FragmentHomeListBinding) {
        super.onDestroyBinding(binding)
        snapshotView?.adapter = null
        snapshotView = null
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

    /** Fork pessoal: a grade de verdade chegou enquanto a foto rolava; entra quando parar. */
    private var albumsWaitingIdle = false

    private fun updateAlbums(albums: List<Album>) {
        val overlay = snapshotView
        if (
            overlay != null &&
                albums.isNotEmpty() &&
                overlay.scrollState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
        ) {
            // Trocar a foto pela grade no meio de uma rolagem rápida dá um tranco: espera parar.
            albumsWaitingIdle = true
            return
        }
        albumsWaitingIdle = false
        albumAdapter.update(albums, homeModel.albumInstructions.consume())
        if (albums.isNotEmpty()) hideSnapshot(albums)
        // Gera em segundo plano as miniaturas que faltam, no tamanho exato das células.
        val recycler = requireBinding().homeRecycler
        recycler.doOnLayout {
            val cell = (recycler.layoutManager as? AlbumGridLayoutManager)?.cellSize() ?: 0
            val halfGap = recycler.resources.getDimensionPixelSize(R.dimen.spacing_album_grid_half_gap)
            gridThumbWarmer.warm(albums, cell - 2 * halfGap)
            // Fork pessoal: guarda a foto da grade completa para a próxima abertura.
            if (USE_SNAPSHOT && homeModel.albumFilter.value.isEmpty && homeModel.query.value.isEmpty()) {
                GridSnapshot.save(recycler.context, albums, cell - 2 * halfGap, gridThumbStore)
            }
            // Fork pessoal: abertura -> o grid já está montado; um instante para as capas da tela
            // (e da tela seguinte) carregarem, e a tela de carregamento sai.
            if (albums.isNotEmpty() && !org.oxycblt.auxio.home.StartupGate.ready.value) {
                org.oxycblt.auxio.home.StartupGate.coversProgress(0.3f)
                recycler.postDelayed({ org.oxycblt.auxio.home.StartupGate.coversProgress(0.7f) }, 150)
                recycler.postDelayed({ org.oxycblt.auxio.home.StartupGate.markReady() }, COVERS_WAIT_MS)
            }
        }
    }

    /** Fork pessoal: mostra o resumo do filtro ativo e abre espaço para ele no topo da grade. */
    private fun updateFilter(filter: AlbumFilter) {
        val binding = requireBinding()
        val chip = binding.homeFilterChip
        if (filter.isEmpty) {
            binding.homeFilterBar.isVisible = false
            binding.homeRecycler.updatePadding(top = 0)
            return
        }
        chip.text = filter.describe(requireContext())
        binding.homeFilterBar.isVisible = true
        binding.homeRecycler.updatePadding(
            top = resources.getDimensionPixelSize(R.dimen.size_album_filter_space)
        )
        binding.homeRecycler.scrollToPosition(0)
    }

    /** Fork pessoal: ▶ do filtro -> toca os álbuns filtrados (em ordem ou embaralhados) numa fila nova. */
    private fun showPlayFilterMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, R.string.lbl_play_in_order)
        popup.menu.add(0, 2, 1, R.string.lbl_play_shuffled)
        popup.setOnMenuItemClickListener { item ->
            val songs = homeModel.shownAlbumSongs()
            val name = homeModel.albumFilter.value.describe(requireContext())
            if (songs.isNotEmpty()) {
                val ok = playbackModel.playInNewQueue(songs, name, item.itemId == 2)
                if (!ok) {
                    android.widget.Toast.makeText(
                            requireContext(),
                            R.string.err_play_filter,
                            android.widget.Toast.LENGTH_LONG,
                        )
                        .show()
                }
            }
            true
        }
        popup.show()
    }

    private fun updateNoMusicIndicator(empty: Boolean, indexingState: IndexingState?) {
        val binding = requireBinding()
        // Fork pessoal: com a foto da grade na tela (abertura), nada de aviso de biblioteca vazia.
        if (snapshotView != null) {
            binding.homeNoMusic.isInvisible = true
            binding.homeNoMusicAction.isVisible = false
            return
        }
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

    private companion object {
        const val USE_SNAPSHOT = false
        const val COVERS_WAIT_MS = 350L
    }
}
