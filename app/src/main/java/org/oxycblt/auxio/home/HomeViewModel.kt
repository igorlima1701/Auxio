/*
 * Copyright (c) 2021 Auxio Project
 * HomeViewModel.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.oxycblt.auxio.home.filter.AlbumFilter
import org.oxycblt.auxio.home.tabs.Tab
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.rating.AlbumCollections
import org.oxycblt.auxio.rating.AlbumRatingTagSync
import org.oxycblt.auxio.rating.AlbumRatings
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * The ViewModel for managing the tab data and lists of the home view.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@HiltViewModel
class HomeViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val albumRatingTagSync: AlbumRatingTagSync,
    private val listSettings: ListSettings,
    private val playbackSettings: PlaybackSettings,
    homeGeneratorFactory: HomeGenerator.Factory,
) : ViewModel(), HomeGenerator.Invalidator {
    /** A list of [Song]s, sorted by the preferred [Sort], to be shown in the home view. */
    val songList: StateFlow<List<Song>>
        field = MutableStateFlow(listOf<Song>())

    /** Instructions for how to update [songList] in the UI. */
    val songInstructions: Event<UpdateInstructions>
        field = MutableEvent<UpdateInstructions>()

    /** The current [Sort] used for [songList]. */
    val songSort: Sort
        get() = listSettings.songSort

    /** The [PlaySong] instructions to use when playing a [Song]. */
    val playWith
        get() = playbackSettings.playInListWith

    /** A list of [Album]s, sorted by the preferred [Sort], to be shown in the home view. */
    val albumList: StateFlow<List<Album>>
        field = MutableStateFlow(listOf<Album>())

    /** Instructions for how to update [albumList] in the UI. */
    val albumInstructions: Event<UpdateInstructions>
        field = MutableEvent<UpdateInstructions>()

    /** Fork pessoal: filtro (nota e/ou ano) aplicado a [albumList]. */
    val albumFilter: StateFlow<AlbumFilter>
        field = MutableStateFlow(AlbumFilter())

    /** Fork pessoal: todos os anos presentes na biblioteca, do mais novo ao mais antigo. */
    val albumYears: List<Int>
        get() =
            allAlbums
                .flatMap { album -> album.dates?.let { it.min.year..it.max.year } ?: emptyList() }
                .distinct()
                .sortedDescending()

    /** Fork pessoal: pedido para ir a uma aba da tela inicial (barra de abas, ano no player). */
    val showTabEvent: Event<MusicType>
        field = MutableEvent<MusicType>()

    /** Fork pessoal: página principal visível (para a barra de abas destacar o ícone certo). */
    enum class MainPage {
        HOME,
        PLAYBACK,
        QUEUE,
    }

    val mainPage: StateFlow<MainPage>
        field = MutableStateFlow(MainPage.HOME)

    fun setMainPage(page: MainPage) {
        mainPage.value = page
    }

    /** Fork pessoal: pedido para abrir a busca geral. */
    val openSearchEvent: Event<Unit>
        field = MutableEvent<Unit>()

    /** Fork pessoal: texto do campo de busca no topo, aplicado à aba atual. */
    val query: StateFlow<String>
        field = MutableStateFlow("")

    /** Todos os álbuns, antes do filtro por ano. */
    private var allAlbums = listOf<Album>()

    /** The current [Sort] used for [albumList]. */
    val albumSort: Sort
        get() = listSettings.albumSort

    private val _artistList = MutableStateFlow(listOf<Artist>())
    /**
     * A list of [Artist]s, sorted by the preferred [Sort], to be shown in the home view. Note that
     * if "Hide collaborators" is on, this list will not include collaborator [Artist]s.
     */
    val artistList: MutableStateFlow<List<Artist>>
        get() = _artistList

    /** Instructions for how to update [artistList] in the UI. */
    val artistInstructions: Event<UpdateInstructions>
        field = MutableEvent<UpdateInstructions>()

    /** The current [Sort] used for [artistList]. */
    val artistSort: Sort
        get() = listSettings.artistSort

    /** A list of [Genre]s, sorted by the preferred [Sort], to be shown in the home view. */
    val genreList: StateFlow<List<Genre>>
        field = MutableStateFlow(listOf<Genre>())

    /** Instructions for how to update [genreList] in the UI. */
    val genreInstructions: Event<UpdateInstructions>
        field = MutableEvent<UpdateInstructions>()

    /** The current [Sort] used for [genreList]. */
    val genreSort: Sort
        get() = listSettings.genreSort

    /** A list of [Playlist]s, sorted by the preferred [Sort], to be shown in the home view. */
    val playlistList: StateFlow<List<Playlist>>
        field = MutableStateFlow(listOf<Playlist>())

    val empty: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Instructions for how to update [genreList] in the UI. */
    val playlistInstructions: Event<UpdateInstructions>
        field = MutableEvent<UpdateInstructions>()

    /** The current [Sort] used for [genreList]. */
    val playlistSort: Sort
        get() = listSettings.playlistSort

    private val homeGenerator = homeGeneratorFactory.create(this)

    /**
     * A list of [MusicType] corresponding to the current [Tab] configuration, excluding invisible
     * [Tab]s.
     */
    var currentTabTypes = forkTabOrder(homeGenerator.tabs())
        private set

    private val _currentTabType = MutableStateFlow(currentTabTypes[0])
    /** The [MusicType] of the currently shown [Tab]. */
    val currentTabType: StateFlow<MusicType> = _currentTabType

    /**
     * A marker to re-create all library tabs, usually initiated by a settings change. When this
     * flag is true, all tabs (and their respective ViewPager2 fragments) will be re-created from
     * scratch.
     */
    val recreateTabs: Event<Unit>
        field = MutableEvent<Unit>()

    private val _isFastScrolling = MutableStateFlow(false)
    /** A marker for whether the user is fast-scrolling in the home view or not. */
    val isFastScrolling: StateFlow<Boolean> = _isFastScrolling

    val showOuter: Event<Outer>
        field = MutableEvent<Outer>()

    val chooseMusicLocations: Event<Unit>
        field = MutableEvent<Unit>()

    init {
        homeGenerator.attach()
        // Fork pessoal: quando uma nota muda, reordena (se ordenado por nota) e refiltra.
        viewModelScope.launch {
            AlbumRatings.version.drop(1).collect {
                allAlbums = homeGenerator.albums()
                albumInstructions.put(UpdateInstructions.Diff)
                albumList.value = filterAlbums(allAlbums)
            }
        }
        // Fork pessoal: saiu da aba Playlists -> a lista automática não reabre mais sozinha.
        viewModelScope.launch {
            currentTabType.collect {
                if (it != MusicType.PLAYLISTS) {
                    org.oxycblt.auxio.home.auto.AutoPlaylistDialog.forgetReopen()
                }
            }
        }
        // Fork pessoal: coleções mudaram -> refiltra (se o filtro usa coleções).
        viewModelScope.launch {
            AlbumCollections.version.drop(1).collect {
                val f = albumFilter.value
                if (f.collections.isNotEmpty() || f.notCollections.isNotEmpty()) {
                    albumInstructions.put(UpdateInstructions.Diff)
                    albumList.value = filterAlbums(allAlbums)
                }
            }
        }
    }

    override fun onCleared() {
        homeGenerator.release()
    }

    override fun invalidateEmpty() {
        empty.value = homeGenerator.empty()
    }

    override fun invalidateMusic(type: MusicType, instructions: UpdateInstructions) {
        when (type) {
            MusicType.SONGS -> {
                songInstructions.put(instructions)
                songList.value = matchQuery(homeGenerator.songs()) { listOf(it.name.resolve(context), it.album.name.resolve(context)) + it.artists.map { a -> a.name.resolve(context) } }
            }
            MusicType.ALBUMS -> {
                allAlbums = homeGenerator.albums()
                AlbumRatings.resolvePending(allAlbums)
                AlbumCollections.resolvePending(allAlbums)
                if (allAlbums.isNotEmpty()) {
                    albumRatingTagSync.importIfNeeded(allAlbums)
                    albumRatingTagSync.resyncIfNeeded(allAlbums)
                }
                albumInstructions.put(
                    if (albumFilter.value.isEmpty) instructions else UpdateInstructions.Replace(0)
                )
                albumList.value = filterAlbums(allAlbums)
            }
            MusicType.ARTISTS -> {
                artistInstructions.put(instructions)
                _artistList.value = matchQuery(homeGenerator.artists()) { listOf(it.name.resolve(context)) }
            }
            MusicType.GENRES -> {
                genreInstructions.put(instructions)
                genreList.value = matchQuery(homeGenerator.genres()) { listOf(it.name.resolve(context)) }
            }
            MusicType.PLAYLISTS -> {
                playlistInstructions.put(instructions)
                playlistList.value = matchQuery(homeGenerator.playlists()) { listOf(it.name.resolve(context)) }
            }
        }
    }

    override fun invalidateTabs() {
        currentTabTypes = forkTabOrder(homeGenerator.tabs())
        recreateTabs.put(Unit)
    }

    /**
     * Apply a new [Sort] to [songList].
     *
     * @param sort The [Sort] to apply.
     */
    fun applySongSort(sort: Sort) {
        listSettings.songSort = sort
    }

    /**
     * Apply a new [Sort] to [albumList].
     *
     * @param sort The [Sort] to apply.
     */
    fun applyAlbumSort(sort: Sort) {
        listSettings.albumSort = sort
    }

    /** Fork pessoal: mostra na aba Álbuns só os álbuns de [year] (mantendo o filtro de nota). */
    fun showAlbumsOfYear(year: Int) {
        setAlbumFilter(albumFilter.value.copy(years = setOf(year), notYears = emptySet()))
        // "Voltar" depois disso tira o filtro e mostra o grid completo.
        yearFilterFromPlayer = true
        // Mostra a lista de álbuns (fecha o álbum que estiver aberto na aba).
        nextTabToGrid = true
        showTab(MusicType.ALBUMS)
    }

    /** Fork pessoal: troca o filtro da aba Álbuns. */
    fun setAlbumFilter(filter: AlbumFilter) {
        yearFilterFromPlayer = false
        if (filter == albumFilter.value) return
        albumFilter.value = filter
        refilterAlbums()
    }

    /** Fork pessoal: volta a mostrar todos os álbuns. */
    fun clearAlbumFilter() = setAlbumFilter(AlbumFilter())

    private fun refilterAlbums() {
        albumInstructions.put(UpdateInstructions.Replace(0))
        albumList.value = filterAlbums(allAlbums)
    }

    private fun filterAlbums(albums: List<Album>): List<Album> {
        val filter = albumFilter.value
        val filtered = if (filter.isEmpty) albums else albums.filter(filter::matches)
        return matchQuery(filtered) { album ->
            listOf(album.name.resolve(context)) + album.artists.map { it.name.resolve(context) }
        }
    }

    private var lastRandom: Album? = null

    /**
     * Fork pessoal: sorteia um álbum entre os que estão aparecendo (respeita filtros e busca), sem
     * repetir o último sorteado.
     */
    fun randomAlbum(): Album? {
        val shown = albumList.value
        val pool = if (shown.size > 1) shown - setOfNotNull(lastRandom) else shown
        return pool.randomOrNull()?.also { lastRandom = it }
    }

    /** Fork pessoal: músicas dos álbuns que estão aparecendo, álbum por álbum, na ordem do disco. */
    fun shownAlbumSongs(): List<Song> =
        albumList.value.flatMap { listSettings.albumSongSort.songs(it.songs) }

    /** Fork pessoal: uma coleção foi renomeada (ou excluída, se [new] for null). */
    fun renameCollectionInFilter(old: String, new: String?) {
        val current = albumFilter.value
        val next = current.renameCollection(old, new)
        if (next != current) setAlbumFilter(next) else refilterAlbums()
    }

    /** Fork pessoal: se a tela inicial está visível (não há álbum/artista aberto por cima). */
    var homeVisible = false

    /** Fork pessoal: o filtro de ano atual veio do toque no ano, no player. */
    var yearFilterFromPlayer = false

    /** Fork pessoal: pedido para a grade de álbuns mostrar este álbum (o que está tocando). */
    val revealAlbumEvent: Event<Album>
        field = MutableEvent<Album>()

    fun revealAlbum(album: Album) {
        revealAlbumEvent.put(album)
    }

    /** Fork pessoal: a próxima troca de aba deve ir para a lista, não para a tela salva da aba. */
    var nextTabToGrid = false

    /** Fork pessoal: aba que a tela inicial deve mostrar quando for recriada. */
    var forcedTab: MusicType? = null

    /** Fork pessoal: troca a aba atual na hora (a tela inicial acompanha). */
    fun forceTab(type: MusicType) {
        forcedTab = type
        _currentTabType.value = type
    }

    /** Fork pessoal: pedido para rolar a lista de uma aba até o topo. */
    val scrollTopEvent: Event<MusicType>
        field = MutableEvent<MusicType>()

    /**
     * Fork pessoal: vai para uma aba da tela inicial. Se já estiver nela, rola a lista até o topo.
     */
    fun showTab(type: MusicType) {
        if (homeVisible && mainPage.value == MainPage.HOME && currentTabType.value == type) {
            nextTabToGrid = false
            scrollTopEvent.put(type)
        } else {
            showTabEvent.put(type)
        }
    }

    /** Fork pessoal: abre a busca geral. */
    fun openSearch() {
        openSearchEvent.put(Unit)
    }

    /** Fork pessoal: atualiza a busca do topo e refaz as listas. */
    fun setQuery(text: String) {
        val normalized = normalize(text)
        if (normalized == query.value) return
        query.value = normalized
        for (type in MusicType.entries) invalidateMusic(type, UpdateInstructions.Replace(0))
    }

    /** Mantém os itens em que algum dos textos contém a busca (sem diferenciar acentos/maiúsculas). */
    private inline fun <T> matchQuery(items: List<T>, texts: (T) -> List<String>): List<T> {
        val q = query.value
        if (q.isEmpty()) return items
        return items.filter { item -> texts(item).any { normalize(it).contains(q) } }
    }

    private fun normalize(text: String) =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()

    /** Ordem das páginas ao deslizar: Álbuns, Artistas, Playlists e depois Músicas e Gêneros. */
    private fun forkTabOrder(tabs: List<MusicType>): List<MusicType> {
        val order =
            listOf(
                MusicType.ALBUMS,
                MusicType.ARTISTS,
                MusicType.PLAYLISTS,
                MusicType.SONGS,
                MusicType.GENRES,
            )
        return tabs.sortedBy { order.indexOf(it) }
    }

    private companion object {
        val DIACRITICS = Regex("\\p{Mn}+")
    }

    /**
     * Apply a new [Sort] to [artistList].
     *
     * @param sort The [Sort] to apply.
     */
    fun applyArtistSort(sort: Sort) {
        listSettings.artistSort = sort
    }

    /**
     * Apply a new [Sort] to [genreList].
     *
     * @param sort The [Sort] to apply.
     */
    fun applyGenreSort(sort: Sort) {
        listSettings.genreSort = sort
    }

    /**
     * Apply a new [Sort] to [playlistList].
     *
     * @param sort The [Sort] to apply.
     */
    fun applyPlaylistSort(sort: Sort) {
        listSettings.playlistSort = sort
    }

    /**
     * Update [currentTabType] to reflect a new ViewPager2 position
     *
     * @param pagerPos The new position of the ViewPager2 instance.
     */
    fun synchronizeTabPosition(pagerPos: Int) {
        L.d("Updating current tab to ${currentTabTypes[pagerPos]}")
        _currentTabType.value = currentTabTypes[pagerPos]
    }

    /**
     * Update whether the user is fast scrolling or not in the home view.
     *
     * @param isFastScrolling true if the user is currently fast scrolling, false otherwise.
     */
    fun setFastScrolling(isFastScrolling: Boolean) {
        L.d("Updating fast scrolling state: $isFastScrolling")
        _isFastScrolling.value = isFastScrolling
    }

    fun startChooseMusicLocations() {
        chooseMusicLocations.put(Unit)
    }

    fun showSettings() {
        showOuter.put(Outer.Settings)
    }

    fun showAbout() {
        showOuter.put(Outer.About)
    }
}

sealed interface Outer {
    data object Settings : Outer

    data object About : Outer
}
