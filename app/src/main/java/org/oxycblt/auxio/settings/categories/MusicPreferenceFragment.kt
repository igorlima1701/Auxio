/*
 * Copyright (c) 2023 Auxio Project
 * MusicPreferenceFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings.categories

import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.util.navigateSafe
import timber.log.Timber as L

/**
 * "Content" settings.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class MusicPreferenceFragment : BasePreferenceFragment(R.xml.preferences_music) {
    private val musicModel: MusicViewModel by viewModels()
    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var musicSettings: org.oxycblt.auxio.music.MusicSettings

    /** Fork pessoal: edita a lista de nomes que nunca são separados (um por linha). */
    private fun showSeparatorExceptions() {
        val context = requireContext()
        val input =
            android.widget.EditText(context).apply {
                setText(musicSettings.separatorExceptions)
                minLines = 6
                maxLines = 14
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        org.oxycblt.auxio.util.ClearText.attach(input)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame =
            android.widget.FrameLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.set_separator_exceptions)
            .setMessage(R.string.lng_separator_exceptions_help)
            .setView(frame)
            .setPositiveButton(R.string.lbl_tag_save) { _, _ ->
                val text =
                    input.text
                        ?.toString()
                        ?.lines()
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.joinToString("\n") ?: ""
                if (text != musicSettings.separatorExceptions) {
                    musicSettings.separatorExceptions = text
                    musicModel.refresh()
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
            .window
            ?.setWindowAnimations(0)
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        if (preference.key == getString(R.string.set_key_separators)) {
            L.d("Navigating to separator dialog")
            findNavController().navigateSafe(MusicPreferenceFragmentDirections.separatorsSettings())
        }
    }

    override fun onSetupPreference(preference: Preference) {
        if (preference.key == "fork_separator_exceptions_pref") {
            preference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    showSeparatorExceptions()
                    true
                }
        }
        if (preference.key == getString(R.string.set_key_cover_mode)) {
            L.d("Configuring cover mode setting")
            preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                L.d("Cover mode changed, reloading music")
                musicModel.refresh()
                true
            }
        }
        if (preference.key == getString(R.string.set_key_square_covers)) {
            L.d("Configuring square cover setting")
            preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                L.d("Cover mode changed, resetting image memory cache")
                imageLoader.memoryCache?.clear()
                true
            }
        }
        if (preference.key == getString(R.string.set_key_with_hidden)) {
            L.d("Configuring ignore hidden files setting")
            preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                L.d("Ignore hidden files setting changed, reloading music")
                musicModel.refresh()
                true
            }
        }
    }
}
