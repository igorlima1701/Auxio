/*
 * Copyright (c) 2021 Auxio Project
 * MainFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.google.android.material.R as MR
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.transition.MaterialFadeThrough
import com.leinardi.android.speeddial.SpeedDialActionItem
import com.leinardi.android.speeddial.SpeedDialView
import dagger.hilt.android.AndroidEntryPoint
import java.lang.reflect.Method
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import org.oxycblt.auxio.databinding.FragmentMainBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.detail.Show
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.home.HomeFragmentDirections
import org.oxycblt.auxio.home.Outer
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.OpenPanel
import org.oxycblt.auxio.playback.PlaybackBottomSheetBehavior
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.queue.QueueBottomSheetBehavior
import org.oxycblt.auxio.ui.DialogAwareNavigationListener
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.coordinatorLayoutBehavior
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.getDimen
import org.oxycblt.auxio.util.lazyReflectedMethod
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.unlikelyToBeNull
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.auxio.util.replaceSystemBarInsetsCompat
import androidx.core.view.updatePadding
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A wrapper around the home fragment that shows the playback fragment and high-level navigation.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class MainFragment :
    ViewBindingFragment<FragmentMainBinding>(),
    ViewTreeObserver.OnPreDrawListener,
    SpeedDialView.OnActionSelectedListener {
    private val musicModel: MusicViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private var sheetBackCallback: PagesBackPressedCallback? = null
    private var currentPage = Page.HOME
    private var detailBackCallback: DetailBackPressedCallback? = null
    private var selectionBackCallback: SelectionBackPressedCallback? = null
    private var speedDialBackCallback: SpeedDialBackPressedCallback? = null
    private var navigationListener: DialogAwareNavigationListener? = null
    private var lastInsets: WindowInsets? = null
    private var elevationNormal = 0f
    private var normalCornerSize = 0f
    private var maxScaleXDistance = 0f
    private var sheetRising: Boolean? = null
    @Inject lateinit var uiSettings: UISettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fork pessoal: sem animação ao abrir configurações/sobre.
        enterTransition = null
        exitTransition = null
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentMainBinding.inflate(inflater)

    @SuppressLint("PrivateResource")
    override fun onBindingCreated(binding: FragmentMainBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        elevationNormal = binding.context.getDimen(MR.dimen.m3_sys_elevation_level1)

        // Currently all back press callbacks are handled in MainFragment, as it's not guaranteed
        // that instantiating these callbacks in their respective fragments would result in the
        // correct order.
        sheetBackCallback = PagesBackPressedCallback()
        val detailBackCallback =
            DetailBackPressedCallback(detailModel).also { detailBackCallback = it }
        val selectionBackCallback =
            SelectionBackPressedCallback(listModel).also { selectionBackCallback = it }
        speedDialBackCallback = SpeedDialBackPressedCallback()

        navigationListener = DialogAwareNavigationListener(::onExploreNavigate)

        // --- UI SETUP ---
        val context = requireActivity()

        // Fork pessoal: a barra de abas fica embaixo e recebe a margem da barra do sistema; as
        // páginas acima dela não recebem margem inferior.
        binding.mainColumn.setOnApplyWindowInsetsListener { _, insets ->
            lastInsets = insets
            val bars = insets.systemBarInsetsCompat
            binding.playbackBarFragment.updatePadding(bottom = bars.bottom)
            binding.playbackPage.updatePadding(top = bars.top)
            binding.queuePage.updatePadding(top = bars.top)
            binding.mainExplore.dispatchApplyWindowInsets(
                insets.replaceSystemBarInsetsCompat(bars.left, bars.top, bars.right, 0)
            )
            val noVertical = insets.replaceSystemBarInsetsCompat(bars.left, 0, bars.right, 0)
            binding.playbackPage.dispatchApplyWindowInsets(noVertical)
            binding.queuePage.dispatchApplyWindowInsets(noVertical)
            binding.playbackBarFragment.dispatchApplyWindowInsets(noVertical)
            @Suppress("DEPRECATION") insets.consumeSystemWindowInsets()
        }

        // Fork pessoal: deslizar para os lados troca de página:
        // Fila <-> Player <-> Álbuns <-> Artistas <-> Playlists.
        binding.mainExplore.apply {
            shouldTrack = {
                currentPage == Page.HOME &&
                    playbackModel.song.value != null &&
                    binding.exploreNavHost.findNavController().currentDestination?.id ==
                        R.id.home_fragment &&
                    homeModel.currentTabType.value == homeModel.currentTabTypes.firstOrNull()
            }
            onSwipe = { direction -> if (direction < 0) showPage(Page.PLAYBACK) }
        }
        binding.playbackPage.apply {
            excludedIds = listOf(R.id.playback_pager, R.id.playback_seek_bar)
            onSwipe = { direction ->
                if (direction > 0) {
                    showPage(Page.HOME)
                    homeModel.showTab(MusicType.ALBUMS)
                } else {
                    showPage(Page.QUEUE)
                }
            }
        }
        binding.queuePage.onSwipe = { direction -> if (direction > 0) showPage(Page.PLAYBACK) }

        binding.mainScrim.setOnClickListener { binding.homeNewPlaylistFab.close() }
        binding.sheetScrim.setOnClickListener { binding.homeNewPlaylistFab.close() }
        binding.homeShuffleFab.setOnClickListener { playbackModel.shuffleAll() }
        binding.homeNewPlaylistFab.apply {
            inflate(R.menu.new_playlist_actions)
            setOnActionSelectedListener(this@MainFragment)
            setChangeListener(::updateSpeedDial)
        }

        forceHideAllFabs()
        updateSpeedDial(false)
        updateFabVisibility(
            binding,
            homeModel.songList.value,
            homeModel.isFastScrolling.value,
            homeModel.currentTabType.value,
        )

        // --- VIEWMODEL SETUP ---
        // This has to be done here instead of the playback panel to make sure that it's prioritized
        // by StateFlow over any detail fragment.
        // FIXME: This is a consequence of sharing events across several consumers. There has to be
        //  a better way of doing this.
        collect(detailModel.toShow.flow, ::handleShow)
        collectImmediately(detailModel.editedPlaylist, detailBackCallback::invalidateEnabled)
        collectImmediately(homeModel.showOuter.flow, ::handleShowOuter)
        collect(homeModel.showTabEvent.flow, ::handleShowTab)
        collect(homeModel.openSearchEvent.flow, ::handleOpenSearch)
        collectImmediately(homeModel.currentTabType, ::updateCurrentTab)
        collectImmediately(homeModel.songList, homeModel.isFastScrolling, ::updateFab)
        collectImmediately(musicModel.indexingState, ::updateIndexerState)
        collectImmediately(listModel.selected, selectionBackCallback::invalidateEnabled)
        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.openPanel.flow, ::handlePanel)
    }

    override fun onStart() {
        super.onStart()
        val binding = requireBinding()
        // Once we add the destination change callback, we will receive another initialization call,
        // so handle that by resetting the flag.
        requireNotNull(navigationListener) { "NavigationListener was not available" }
            .attach(binding.exploreNavHost.findNavController())
        // Listener could still reasonably fire even if we clear the binding, attach/detach
        // our pre-draw listener our listener in onStart/onStop respectively.
        binding.root.viewTreeObserver.addOnPreDrawListener(this@MainFragment)
    }

    override fun onResume() {
        super.onResume()
        // Override the back pressed listener so we can map back navigation to collapsing
        // navigation, navigation out of detail views, etc. We have to do this here in
        // onResume or otherwise the FragmentManager will have precedence.
        requireActivity().onBackPressedDispatcher.apply {
            addCallback(viewLifecycleOwner, requireNotNull(speedDialBackCallback))
            addCallback(viewLifecycleOwner, requireNotNull(selectionBackCallback))
            addCallback(viewLifecycleOwner, requireNotNull(detailBackCallback))
            addCallback(viewLifecycleOwner, requireNotNull(sheetBackCallback))
        }
    }

    override fun onStop() {
        super.onStop()
        val binding = requireBinding()
        requireNotNull(navigationListener) { "NavigationListener was not available" }
            .release(binding.exploreNavHost.findNavController())
        binding.root.viewTreeObserver.removeOnPreDrawListener(this)
    }

    override fun onDestroyBinding(binding: FragmentMainBinding) {
        super.onDestroyBinding(binding)
        speedDialBackCallback = null
        sheetBackCallback = null
        detailBackCallback = null
        selectionBackCallback = null
        navigationListener = null
        binding.homeNewPlaylistFab.setChangeListener(null)
        binding.homeNewPlaylistFab.setOnActionSelectedListener(null)
    }

    override fun onPreDraw(): Boolean {
        // Fork pessoal: sem gavetas, só falta manter o contêiner dos botões flutuantes sem
        // capturar toques quando eles estão escondidos.
        val binding = requireBinding()
        binding.mainFabContainer.isVisible =
            binding.homeNewPlaylistFab.mainFab.isVisible || binding.homeShuffleFab.isVisible
        return true
    }

    override fun onActionSelected(actionItem: SpeedDialActionItem): Boolean {
        when (actionItem.id) {
            R.id.action_new_playlist -> {
                L.d("Creating playlist")
                musicModel.createPlaylist()
            }
            R.id.action_import_playlist -> {
                L.d("Importing playlist")
                musicModel.importPlaylist()
            }
            else -> {}
        }
        // Returning false to close the speed dial results in no animation, manually close instead.
        // Adapted from Material Files: https://github.com/zhanghai/MaterialFiles
        requireBinding().homeNewPlaylistFab.close()
        return true
    }

    private fun onExploreNavigate() {
        listModel.dropSelection()
        updateFabVisibility(
            requireBinding(),
            homeModel.songList.value,
            homeModel.isFastScrolling.value,
            homeModel.currentTabType.value,
        )
    }

    private fun updateCurrentTab(tabType: MusicType) {
        val binding = requireBinding()
        updateFabVisibility(
            binding,
            homeModel.songList.value,
            homeModel.isFastScrolling.value,
            tabType,
        )
    }

    private fun updateIndexerState(state: IndexingState?) {
        if (state is IndexingState.Completed && state.error == null) {
            L.d("Received ok response")
            val binding = requireBinding()
            updateFabVisibility(
                binding,
                homeModel.songList.value,
                homeModel.isFastScrolling.value,
                homeModel.currentTabType.value,
            )
        }
    }

    private fun updateFab(songs: List<Song>, isFastScrolling: Boolean) {
        val binding = requireBinding()
        updateFabVisibility(binding, songs, isFastScrolling, homeModel.currentTabType.value)
    }

    private fun updateFabVisibility(
        binding: FragmentMainBinding,
        songs: List<Song>,
        isFastScrolling: Boolean,
        tabType: MusicType,
    ) {
        // If there are no songs, it's likely that the library has not been loaded, so
        // displaying the shuffle FAB makes no sense. We also don't want the fast scroll
        // popup to overlap with the FAB, so we hide the FAB when fast scrolling too.
        if (shouldHideAllFabs(binding, songs, isFastScrolling)) {
            L.d("Hiding fab: [empty: ${songs.isEmpty()} scrolling: $isFastScrolling]")
            forceHideAllFabs()
        } else {
            if (tabType != MusicType.PLAYLISTS) {
                // Fork pessoal: sem o botão redondo de aleatório (o sorteio de álbum fica no topo).
                forceHideAllFabs()
                return
            }
            if (tabType != MusicType.PLAYLISTS) {
                if (binding.homeShuffleFab.isOrWillBeShown) {
                    return
                }

                if (binding.homeNewPlaylistFab.mainFab.isOrWillBeShown) {
                    L.d("Animating transition")
                    binding.homeNewPlaylistFab.hide(
                        object : FloatingActionButton.OnVisibilityChangedListener() {
                            override fun onHidden(fab: FloatingActionButton) {
                                super.onHidden(fab)
                                if (
                                    shouldHideAllFabs(
                                        binding,
                                        homeModel.songList.value,
                                        homeModel.isFastScrolling.value,
                                    )
                                ) {
                                    return
                                }
                                binding.homeShuffleFab.show()
                            }
                        }
                    )
                } else {
                    L.d("Showing immediately")
                    binding.homeShuffleFab.show()
                }
            } else {
                L.d("Showing playlist button")
                if (binding.homeNewPlaylistFab.mainFab.isOrWillBeShown) {
                    return
                }

                if (binding.homeShuffleFab.isOrWillBeShown) {
                    L.d("Animating transition")
                    binding.homeShuffleFab.hide(
                        object : FloatingActionButton.OnVisibilityChangedListener() {
                            override fun onHidden(fab: FloatingActionButton) {
                                super.onHidden(fab)
                                if (
                                    shouldHideAllFabs(
                                        binding,
                                        homeModel.songList.value,
                                        homeModel.isFastScrolling.value,
                                    )
                                ) {
                                    return
                                }
                                binding.homeNewPlaylistFab.show()
                            }
                        }
                    )
                } else {
                    L.d("Showing immediately")
                    binding.homeNewPlaylistFab.show()
                }
            }
        }
    }

    private fun shouldHideAllFabs(
        binding: FragmentMainBinding,
        songs: List<Song>,
        isFastScrolling: Boolean,
    ) =
        binding.exploreNavHost.findNavController().currentDestination?.id != R.id.home_fragment ||
            currentPage != Page.HOME ||
            songs.isEmpty() ||
            isFastScrolling

    private fun forceHideAllFabs() {
        val binding = requireBinding()
        if (binding.homeShuffleFab.isOrWillBeShown) {
            FAB_HIDE_FROM_USER_FIELD.invoke(binding.homeShuffleFab, null, false)
        }
        if (binding.homeNewPlaylistFab.isOpen) {
            binding.homeNewPlaylistFab.close()
        }
        if (binding.homeNewPlaylistFab.mainFab.isOrWillBeShown) {
            FAB_HIDE_FROM_USER_FIELD.invoke(binding.homeNewPlaylistFab.mainFab, null, false)
        }
    }

    private fun updateSpeedDial(open: Boolean) {
        requireNotNull(speedDialBackCallback) { "SpeedDialBackPressedCallback was not available" }
            .invalidateEnabled(open)
        val binding = requireBinding()
        binding.mainScrim.isInvisible = !open
        binding.sheetScrim.isInvisible = !open
    }

    private fun handleShow(show: Show?) {
        when (show) {
            is Show.SongAlbumDetails,
            is Show.ArtistDetails,
            is Show.AlbumDetails -> playbackModel.openMain()
            is Show.SongDetails,
            is Show.SongArtistDecision,
            is Show.AlbumArtistDecision,
            is Show.GenreDetails,
            is Show.PlaylistDetails,
            null -> {}
        }
    }

    /**
     * Fork pessoal: ao trocar de aba pela barra de baixo, volta para a tela inicial (saindo de telas
     * de detalhe) e fecha o player. Quem troca a aba é o HomeFragment.
     */
    private fun handleShowTab(type: MusicType?) {
        if (type == null) return
        showPage(Page.HOME)
        popToHome()
    }

    /** Fork pessoal: busca geral pela barra de baixo. */
    private fun handleOpenSearch(open: Unit?) {
        if (open == null) return
        homeModel.openSearchEvent.consume()
        showPage(Page.HOME)
        popToHome()
        requireBinding().exploreNavHost.findNavController().navigateSafe(HomeFragmentDirections.search())
    }

    private fun popToHome() {
        val navController = requireBinding().exploreNavHost.findNavController()
        if (navController.currentDestination?.id != R.id.home_fragment) {
            navController.popBackStack(R.id.home_fragment, false)
        }
    }

    private fun handleShowOuter(outer: Outer?) {
        val directions =
            when (outer) {
                is Outer.Settings -> MainFragmentDirections.preferences()
                is Outer.About -> MainFragmentDirections.about()
                null -> return
            }
        findNavController().navigateSafe(directions)
        homeModel.showOuter.consume()
    }

    private fun updateSong(song: Song?) {
        // Fork pessoal: sem música, Player e Fila não abrem.
        if (song == null) showPage(Page.HOME)
    }

    private fun handlePanel(panel: OpenPanel?) {
        if (panel == null) return
        L.d("Trying to update panel to $panel")
        when (panel) {
            OpenPanel.MAIN -> showPage(Page.HOME)
            OpenPanel.PLAYBACK -> showPage(Page.PLAYBACK)
            OpenPanel.QUEUE -> showPage(Page.QUEUE)
        }
        playbackModel.openPanel.consume()
    }

    /** Fork pessoal: troca de página na hora, sem animação. */
    private fun showPage(page: Page) {
        val target = if (page != Page.HOME && playbackModel.song.value == null) Page.HOME else page
        val binding = requireBinding()
        currentPage = target
        binding.playbackPage.isInvisible = target != Page.PLAYBACK
        binding.queuePage.isInvisible = target != Page.QUEUE
        binding.mainExplore.isInvisible = target != Page.HOME
        homeModel.setMainPage(
            when (target) {
                Page.HOME -> HomeViewModel.MainPage.HOME
                Page.PLAYBACK -> HomeViewModel.MainPage.PLAYBACK
                Page.QUEUE -> HomeViewModel.MainPage.QUEUE
            }
        )
        requireNotNull(sheetBackCallback) { "PagesBackPressedCallback was not available" }
            .invalidateEnabled()
        updateFabVisibility(
            binding,
            homeModel.songList.value,
            homeModel.isFastScrolling.value,
            homeModel.currentTabType.value,
        )
    }

    private enum class Page {
        HOME,
        PLAYBACK,
        QUEUE,
    }

    /** Voltar: Fila -> Player -> tela anterior. */
    private inner class PagesBackPressedCallback : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (currentPage) {
                Page.QUEUE -> showPage(Page.PLAYBACK)
                Page.PLAYBACK -> showPage(Page.HOME)
                Page.HOME -> {}
            }
        }

        fun invalidateEnabled() {
            isEnabled = currentPage != Page.HOME
        }
    }

    private class DetailBackPressedCallback(private val detailModel: DetailViewModel) :
        OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (detailModel.dropPlaylistEdit()) {
                L.d("Dropped playlist edits")
            }
        }

        fun invalidateEnabled(playlistEdit: List<Song>?) {
            isEnabled = playlistEdit != null
        }
    }

    private class SelectionBackPressedCallback(private val listModel: ListViewModel) :
        OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (listModel.dropSelection()) {
                L.d("Dropped selection")
            }
        }

        fun invalidateEnabled(selection: List<Music>) {
            isEnabled = selection.isNotEmpty()
        }
    }

    private inner class SpeedDialBackPressedCallback : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val binding = requireBinding()
            if (binding.homeNewPlaylistFab.isOpen) {
                binding.homeNewPlaylistFab.close()
            }
        }

        fun invalidateEnabled(open: Boolean) {
            isEnabled = open
        }
    }

    private companion object {
        val FAB_HIDE_FROM_USER_FIELD: Method by
            lazyReflectedMethod(
                FloatingActionButton::class,
                "hide",
                FloatingActionButton.OnVisibilityChangedListener::class,
                Boolean::class,
            )
    }
}
