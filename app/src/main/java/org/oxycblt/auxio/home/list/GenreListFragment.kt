/*
 * Copyright (c) 2021 Auxio Project
 * GenreListFragment.kt is part of Auxio.
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
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.home.genre.GenreNodeFragment
import org.oxycblt.auxio.home.genre.GenreTree
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeListBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.list.recycler.FastScrollRecyclerView
import org.oxycblt.auxio.list.recycler.GenreViewHolder
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMsPopup
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * A [ListFragment] that shows a list of [Genre]s.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class GenreListFragment :
    ListFragment<Genre, FragmentHomeListBinding>(),
    FastScrollRecyclerView.PopupProvider,
    FastScrollRecyclerView.Listener {
    private val homeModel: HomeViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    private val genreAdapter = GenreAdapter(this)

    // Fork pessoal: a aba mostra os gêneros em árvore (subgêneros dentro dos gêneros).
    private val treeAdapter = TreeAdapter()
    private var roots: List<GenreTree.Node> = emptyList()
    private var rows: List<TreeRow> = emptyList()
    private var buildJob: Job? = null

    /** Uma linha da árvore. [path] identifica o lugar (um gênero pode estar em dois pais). */
    private class TreeRow(val node: GenreTree.Node, val depth: Int, val path: String)

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.homeRecycler.apply {
            id = R.id.home_genre_recycler
            adapter = treeAdapter
            popupProvider = this@GenreListFragment
            listener = this@GenreListFragment
        }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_genre_48)
            contentDescription = getString(R.string.lbl_genres)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_genres)

        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }

        collectImmediately(homeModel.genreList, ::updateGenres)
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
        // Fork pessoal: na árvore, a letra do gênero de primeiro nível daquela altura.
        rows.getOrNull(pos)?.let { row ->
            val root = rows.subList(0, pos + 1).lastOrNull { it.depth == 0 } ?: row
            return FastScrollRecyclerView.PopupProvider.PopupData(
                root.node.name.firstOrNull()?.uppercase() ?: "?"
            )
        }
        val genre = homeModel.genreList.value.getOrNull(pos) ?: return null
        // Change how we display the popup depending on the current sort mode.
        return when (homeModel.genreSort.mode) {
            // By Name -> Use Name
            is Sort.Mode.ByName ->
                FastScrollRecyclerView.PopupProvider.PopupData(genre.name.thumb() ?: "?")

            // Duration -> Use compact bucket duration
            is Sort.Mode.ByDuration ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    genre.durationMs.formatDurationMsPopup()
                )

            // Count -> Use song count
            is Sort.Mode.ByCount ->
                FastScrollRecyclerView.PopupProvider.PopupData(genre.songs.size.toString())

            // Unsupported sort, error gracefully
            else -> null
        }
    }

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    override fun onRealClick(item: Genre) {
        detailModel.showGenre(item)
    }

    override fun onOpenMenu(item: Genre) {
        listModel.openMenu(R.menu.parent, item)
    }

    private fun updateGenres(genres: List<Genre>) {
        homeModel.genreInstructions.consume()
        val context = requireContext().applicationContext
        buildJob?.cancel()
        buildJob =
            viewLifecycleOwner.lifecycleScope.launch {
                val built = withContext(Dispatchers.Default) { GenreTree.build(context, genres) }
                roots = built
                lastRoots = built
                refreshRows()
            }
    }

    private fun refreshRows() {
        val out = mutableListOf<TreeRow>()
        fun add(node: GenreTree.Node, depth: Int, parentPath: String) {
            val path = parentPath + "/" + node.key
            out += TreeRow(node, depth, path)
            if (path in expanded) node.children.forEach { add(it, depth + 1, path) }
        }
        roots.forEach { add(it, 0, "") }
        rows = out
        treeAdapter.notifyDataSetChanged()
    }

    private fun toggle(row: TreeRow) {
        if (!expanded.remove(row.path)) expanded += row.path
        refreshRows()
    }

    /** Abre o gênero na tela com os álbuns dele (e dos subgêneros, se houver). */
    private fun open(row: TreeRow) {
        val node = row.node
        NavHostFragment.findNavController(this)
            .navigate(R.id.genre_node_fragment, bundleOf(GenreNodeFragment.ARG_KEY to node.key))
    }

    private inner class TreeAdapter : RecyclerView.Adapter<TreeHolder>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TreeHolder.create(parent)

        override fun onBindViewHolder(holder: TreeHolder, position: Int) {
            val row = rows[position]
            val context = holder.itemView.context
            val density = context.resources.displayMetrics.density
            val node = row.node
            holder.indent.layoutParams.width = (row.depth * 20 * density).toInt()
            holder.indent.requestLayout()
            val hasKids = node.children.isNotEmpty()
            holder.arrow.visibility = if (hasKids) View.VISIBLE else View.INVISIBLE
            holder.arrow.rotation = if (row.path in expanded) 0f else -90f
            holder.arrow.setOnClickListener { if (hasKids) toggle(row) }
            holder.name.text = node.name
            val albums = node.albums.size
            holder.count.text =
                context.resources.getQuantityString(R.plurals.fmt_album_count, albums, albums)
            holder.itemView.setOnClickListener { open(row) }
            holder.itemView.setOnLongClickListener {
                val genre = node.genre
                if (genre != null && !hasKids) listModel.openMenu(R.menu.parent, genre)
                else if (hasKids) toggle(row)
                true
            }
        }
    }

    private class TreeHolder(
        root: View,
        val indent: View,
        val arrow: ImageView,
        val name: TextView,
        val count: TextView,
    ) : RecyclerView.ViewHolder(root) {
        companion object {
            fun create(parent: ViewGroup): TreeHolder {
                val context = parent.context
                val density = context.resources.displayMetrics.density
                fun dp(v: Int) = (v * density).toInt()
                val root =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(56)
                        setPadding(dp(4), 0, dp(16), 0)
                        layoutParams =
                            RecyclerView.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                        val ripple = TypedValue()
                        context.theme.resolveAttribute(
                            android.R.attr.selectableItemBackground,
                            ripple,
                            true,
                        )
                        setBackgroundResource(ripple.resourceId)
                    }
                val indent = View(context)
                root.addView(indent, LinearLayout.LayoutParams(0, 1))
                val arrow =
                    ImageView(context).apply {
                        setImageResource(R.drawable.drop_down_24)
                        scaleType = ImageView.ScaleType.CENTER
                        val ripple = TypedValue()
                        context.theme.resolveAttribute(
                            android.R.attr.selectableItemBackgroundBorderless,
                            ripple,
                            true,
                        )
                        setBackgroundResource(ripple.resourceId)
                    }
                root.addView(arrow, LinearLayout.LayoutParams(dp(48), dp(48)))
                val name =
                    TextView(context).apply {
                        setTextAppearance(
                            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                        )
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                root.addView(
                    name,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                val count =
                    TextView(context).apply {
                        setTextAppearance(
                            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                        )
                        alpha = 0.7f
                        setPadding(dp(12), 0, 0, 0)
                    }
                root.addView(count)
                return TreeHolder(root, indent, arrow, name, count)
            }
        }
    }

    companion object {
        /** Gêneros abertos na árvore (valem enquanto o app está aberto). */
        private val expanded = HashSet<String>()

        /** Última árvore montada (para a tela de um gênero achar o nó). */
        @Volatile var lastRoots: List<GenreTree.Node> = emptyList()
            private set
    }

    private fun updateNoMusicIndicator(empty: Boolean, indexingState: IndexingState?) {
        val binding = requireBinding()
        binding.homeRecycler.isInvisible = empty
        binding.homeNoMusic.isInvisible = !empty
        binding.homeNoMusicAction.isVisible =
            indexingState == null || (empty && indexingState is IndexingState.Completed)
    }

    private fun updateSelection(selection: List<Music>) {
        genreAdapter.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        // Only highlight the genre if it is currently playing, and if the currently
        // playing song is also contained within.
        val genre = (parent as? Genre)?.takeIf { song?.run { genres.contains(it) } ?: false }
        genreAdapter.setPlaying(genre, isPlaying)
    }

    /**
     * A [SelectionIndicatorAdapter] that shows a list of [Genre]s using [GenreViewHolder].
     *
     * @param listener An [SelectableListListener] to bind interactions to.
     */
    private class GenreAdapter(private val listener: SelectableListListener<Genre>) :
        SelectionIndicatorAdapter<Genre, GenreViewHolder>(GenreViewHolder.DIFF_CALLBACK) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            GenreViewHolder.from(parent)

        override fun onBindViewHolder(holder: GenreViewHolder, position: Int) {
            holder.bind(getItem(position), listener)
        }
    }
}
