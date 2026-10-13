/*
 * Copyright (c) 2021 Auxio Project
 * RootPreferenceFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings

import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.util.navigateSafe
import timber.log.Timber as L

/**
 * The [PreferenceFragmentCompat] that displays the root settings list.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class RootPreferenceFragment : BasePreferenceFragment(R.xml.preferences_root) {
    private val musicModel: MusicViewModel by activityViewModels()

    // Fork pessoal: exportar / importar a cópia de segurança.
    private val exportLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
                "application/json"
            )
        ) { uri ->
            if (uri != null) runBackup(export = true, uri = uri)
        }

    private val importLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) runBackup(export = false, uri = uri)
        }

    private fun runBackup(export: Boolean, uri: android.net.Uri) {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        if (export) ForkBackup.export(context, uri) else ForkBackup.import(context, uri)
                    } catch (e: Exception) {
                        L.w(e, "Cópia de segurança falhou")
                        e
                    }
                }
            val activity = activity ?: return@launch
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            when (outcome) {
                is ForkBackup.Result ->
                    if (export) {
                        builder.setMessage(
                            getString(R.string.fmt_fork_backup_exported, outcome.plays, outcome.settings)
                        )
                        builder.setPositiveButton(R.string.lbl_ok, null)
                    } else {
                        // Notas, coleções e configurações são lidas na abertura: o app reinicia.
                        builder.setMessage(
                            getString(R.string.fmt_fork_backup_imported, outcome.plays, outcome.settings)
                        )
                        builder.setCancelable(false)
                        builder.setPositiveButton(R.string.lbl_ok) { _, _ ->
                            activity.finishAffinity()
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    }
                is Exception -> {
                    builder.setMessage(
                        getString(R.string.fmt_fork_backup_failed, outcome.message ?: outcome.toString())
                    )
                    builder.setPositiveButton(R.string.lbl_ok, null)
                }
            }
            builder.show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterTransition = MaterialFadeThrough()
        returnTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        when (preference.key) {
            getString(R.string.set_key_music_dirs) -> {
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.musicLocationsSettings())
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        // Hook generic preferences to their specified preferences
        // TODO: These seem like good things to put into a side navigation view, if I choose to
        //  do one.
        when (preference.key) {
            getString(R.string.set_key_ui) -> {
                L.d("Navigating to UI preferences")
                findNavController().navigateSafe(RootPreferenceFragmentDirections.uiPreferences())
            }
            getString(R.string.set_key_personalize) -> {
                L.d("Navigating to personalization preferences")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.personalizePreferences())
            }
            getString(R.string.set_key_music) -> {
                L.d("Navigating to music preferences")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.musicPreferences())
            }
            getString(R.string.set_key_audio) -> {
                L.d("Navigating to audio preferences")
                findNavController().navigateSafe(RootPreferenceFragmentDirections.audioPeferences())
            }
            getString(R.string.set_key_reindex) -> musicModel.refresh()
            getString(R.string.set_key_rescan) -> musicModel.rescan()
            "fork_backup_export" -> {
                val stamp =
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date())
                exportLauncher.launch("auxio-igor-$stamp.json")
            }
            "fork_backup_import" ->
                importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
            else -> return super.onPreferenceTreeClick(preference)
        }

        return true
    }
}
