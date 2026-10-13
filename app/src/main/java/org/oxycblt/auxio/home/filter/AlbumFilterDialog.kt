/*
 * Copyright (c) 2026 Auxio Project
 * AlbumFilterDialog.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home.filter

import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.PopupMenu
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogAlbumFilterBinding
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.rating.AlbumCollections
import org.oxycblt.auxio.rating.AlbumRatings
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.systemBarInsetsCompat

/**
 * Fork pessoal: escolhe coleções, notas e anos da aba Álbuns. Cada toque já aplica o filtro na
 * hora. Cada opção alterna entre desligada, incluída (✓) e excluída (✕, riscada).
 */
@AndroidEntryPoint
class AlbumFilterDialog : ViewBindingBottomSheetDialogFragment<DialogAlbumFilterBinding>() {
    private val homeModel: HomeViewModel by activityViewModels()

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogAlbumFilterBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogAlbumFilterBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.root.setOnApplyWindowInsetsListener { v, insets ->
            v.updatePadding(bottom = insets.systemBarInsetsCompat.bottom)
            insets
        }

        buildCollections(binding)

        val filter = homeModel.albumFilter.value

        // Notas: "Sem nota", 0, 1★ ... 5★
        val ratingChoices =
            listOf(AlbumFilter.NO_RATING to getString(R.string.lbl_no_rating)) +
                (AlbumRatings.MIN..AlbumRatings.MAX).map {
                    it to
                        if (it == 0) getString(R.string.lbl_rating_zero)
                        else getString(R.string.fmt_rating_stars, it)
                }
        for ((value, label) in ratingChoices) {
            binding.filterRatings.addView(
                makeChip(binding.filterRatings, label, filter.stateOfRating(value)) { state ->
                    val current = homeModel.albumFilter.value
                    homeModel.setAlbumFilter(current.withRating(value, state))
                }
            )
        }

        // Anos presentes na biblioteca, do mais novo ao mais antigo.
        for (year in homeModel.albumYears) {
            binding.filterYears.addView(
                makeChip(binding.filterYears, year.toString(), filter.stateOfYear(year)) { state ->
                    val current = homeModel.albumFilter.value
                    homeModel.setAlbumFilter(current.withYear(year, state))
                }
            )
        }

        binding.filterClear.setOnClickListener {
            homeModel.clearAlbumFilter()
            dismiss()
        }
    }

    /** Coleções: toque alterna o filtro; toque longo renomeia, move ou exclui. */
    private fun buildCollections(binding: DialogAlbumFilterBinding) {
        val group = binding.filterCollections
        group.removeAllViews()
        val filter = homeModel.albumFilter.value
        for (name in AlbumCollections.all()) {
            val chip =
                makeChip(group, name, filter.stateOfCollection(name)) { state ->
                    val current = homeModel.albumFilter.value
                    homeModel.setAlbumFilter(current.withCollection(name, state))
                }
            chip.setOnLongClickListener {
                showCollectionMenu(binding, chip, name)
                true
            }
            group.addView(chip)
        }
        val add =
            (LayoutInflater.from(group.context).inflate(R.layout.item_filter_chip, group, false)
                    as Chip)
                .apply {
                    text = getString(R.string.lbl_new_collection)
                    isCheckable = false
                    setChipIconResource(R.drawable.ic_add_24)
                    isChipIconVisible = true
                    setOnClickListener {
                        CollectionDialogs.askName(
                            requireContext(),
                            "",
                            R.string.lbl_new_collection,
                        ) { name ->
                            AlbumCollections.create(name)
                            if (isAdded) buildCollections(binding)
                        }
                    }
                }
        group.addView(add)
    }

    private fun showCollectionMenu(binding: DialogAlbumFilterBinding, anchor: Chip, name: String) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, R.string.lbl_rename)
        popup.menu.add(0, 2, 1, R.string.lbl_move_left)
        popup.menu.add(0, 3, 2, R.string.lbl_move_right)
        popup.menu.add(0, 4, 3, R.string.lbl_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 ->
                    CollectionDialogs.askName(requireContext(), name, R.string.lbl_rename) { new ->
                        if (AlbumCollections.rename(name, new)) {
                            homeModel.renameCollectionInFilter(name, new)
                        }
                        if (isAdded) buildCollections(binding)
                    }
                2,
                3 -> {
                    AlbumCollections.move(name, if (item.itemId == 2) -1 else 1)
                    buildCollections(binding)
                }
                4 ->
                    CollectionDialogs.confirmDelete(requireContext(), name) {
                        homeModel.renameCollectionInFilter(name, null)
                        if (isAdded) buildCollections(binding)
                    }
            }
            true
        }
        popup.show()
    }

    private fun makeChip(group: ChipGroup, label: String, initial: Int, onChange: (Int) -> Unit) =
        (LayoutInflater.from(group.context).inflate(R.layout.item_filter_chip, group, false)
                as Chip)
            .apply {
                text = label
                isCheckable = false
                val baseBackground = chipBackgroundColor
                val baseText = textColors
                var state = initial
                fun render() {
                    when (state) {
                        1 -> {
                            chipBackgroundColor =
                                ColorStateList.valueOf(
                                    MaterialColors.getColor(
                                        this,
                                        com.google.android.material.R.attr.colorSecondaryContainer,
                                    )
                                )
                            setTextColor(
                                MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorOnSecondaryContainer,
                                )
                            )
                            setChipIconResource(R.drawable.ic_check_24)
                            chipIconTint = textColors
                            isChipIconVisible = true
                            paintFlags = paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                        }
                        -1 -> {
                            chipBackgroundColor =
                                ColorStateList.valueOf(
                                    MaterialColors.getColor(
                                        this,
                                        com.google.android.material.R.attr.colorErrorContainer,
                                    )
                                )
                            setTextColor(
                                MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorOnErrorContainer,
                                )
                            )
                            setChipIconResource(R.drawable.ic_close_24)
                            chipIconTint = textColors
                            isChipIconVisible = true
                            paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                        }
                        else -> {
                            chipBackgroundColor = baseBackground
                            setTextColor(baseText)
                            isChipIconVisible = false
                            paintFlags = paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                        }
                    }
                }
                render()
                setOnClickListener {
                    // desligada -> incluída -> excluída -> desligada
                    state =
                        when (state) {
                            0 -> 1
                            1 -> -1
                            else -> 0
                        }
                    render()
                    onChange(state)
                }
            }

    companion object {
        const val TAG = "auxio.fork.ALBUM_FILTER"
    }
}
