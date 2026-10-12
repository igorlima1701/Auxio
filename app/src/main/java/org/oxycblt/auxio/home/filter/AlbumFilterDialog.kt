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

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogAlbumFilterBinding
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.rating.AlbumRatings
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.systemBarInsetsCompat

/**
 * Fork pessoal: escolhe notas e anos da aba Álbuns. Cada toque já aplica o filtro na hora; dá para
 * marcar várias notas e vários anos ao mesmo tempo.
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
                makeChip(binding.filterRatings, label, value in filter.ratings) { checked ->
                    val current = homeModel.albumFilter.value
                    val ratings =
                        if (checked) current.ratings + value else current.ratings - value
                    homeModel.setAlbumFilter(current.copy(ratings = ratings))
                }
            )
        }

        // Anos presentes na biblioteca, do mais novo ao mais antigo.
        for (year in homeModel.albumYears) {
            binding.filterYears.addView(
                makeChip(binding.filterYears, year.toString(), year in filter.years) { checked ->
                    val current = homeModel.albumFilter.value
                    val years = if (checked) current.years + year else current.years - year
                    homeModel.setAlbumFilter(current.copy(years = years))
                }
            )
        }

        binding.filterClear.setOnClickListener {
            homeModel.clearAlbumFilter()
            dismiss()
        }
    }

    private fun makeChip(
        group: ChipGroup,
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) =
        (LayoutInflater.from(group.context).inflate(R.layout.item_filter_chip, group, false)
                as Chip)
            .apply {
            text = label
            isCheckable = true
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }

    companion object {
        const val TAG = "auxio.fork.ALBUM_FILTER"
    }
}
