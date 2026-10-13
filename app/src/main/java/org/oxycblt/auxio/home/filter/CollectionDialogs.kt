/*
 * Copyright (c) 2026 Auxio Project
 * CollectionDialogs.kt is part of Auxio.
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

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.oxycblt.auxio.R
import org.oxycblt.auxio.rating.AlbumCollections
import org.oxycblt.musikr.Album

/** Fork pessoal: janelas das coleções de álbuns (escolher, criar, renomear, excluir). */
object CollectionDialogs {
    /**
     * Lista de coleções com caixas de marcar para [albums]. Uma coleção aparece marcada se todos
     * os álbuns estão nela; marcar põe todos, desmarcar tira todos.
     */
    fun pick(context: Context, albums: List<Album>) {
        if (albums.isEmpty()) return
        val names = AlbumCollections.all()
        val checked =
            BooleanArray(names.size) { i -> albums.all { names[i] in AlbumCollections.of(it) } }
        val title =
            if (albums.size == 1) context.getString(R.string.lbl_add_to_collection)
            else context.getString(R.string.fmt_add_albums_to_collection, albums.size)
        val builder =
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setNeutralButton(R.string.lbl_new_collection) { _, _ ->
                    askName(context, "", R.string.lbl_new_collection) { name ->
                        val created = AlbumCollections.create(name) ?: return@askName
                        AlbumCollections.setMember(albums, created, true)
                        toastAdded(context, created, albums.size)
                    }
                }
                .setPositiveButton(R.string.lbl_ok, null)
        if (names.isEmpty()) {
            builder.setMessage(R.string.lng_no_collections)
        } else {
            builder.setMultiChoiceItems(names.toTypedArray<CharSequence>(), checked) { _, which, isChecked ->
                AlbumCollections.setMember(albums, names[which], isChecked)
            }
        }
        builder.show()
    }

    private fun toastAdded(context: Context, name: String, count: Int) {
        Toast.makeText(
                context,
                context.getString(R.string.fmt_added_to_collection, count, name),
                Toast.LENGTH_SHORT,
            )
            .show()
    }

    /** Pede um nome de coleção. [onOk] só é chamado com um nome válido e que ainda não exista. */
    fun askName(
        context: Context,
        initial: String,
        titleRes: Int,
        onOk: (String) -> Unit,
    ) {
        val input =
            EditText(context).apply {
                setText(initial)
                setSelection(initial.length)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                imeOptions = EditorInfo.IME_ACTION_DONE
                hint = context.getString(R.string.lng_collection_name_hint)
                isSingleLine = true
            }
        val pad = context.resources.getDimensionPixelSize(R.dimen.spacing_large)
        val container =
            FrameLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.lbl_ok) { _, _ ->
                val text = input.text.toString()
                val clean = AlbumCollections.clean(text)
                when {
                    clean == null ->
                        Toast.makeText(
                                context,
                                R.string.lng_collection_name_invalid,
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    !clean.equals(initial, true) && AlbumCollections.exists(clean) ->
                        Toast.makeText(context, R.string.lng_collection_exists, Toast.LENGTH_LONG)
                            .show()
                    else -> onOk(clean)
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
        input.requestFocus()
    }

    /** Pergunta antes de excluir a coleção [name]. */
    fun confirmDelete(context: Context, name: String, onDeleted: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lbl_delete_collection)
            .setMessage(context.getString(R.string.fmt_delete_collection, name))
            .setPositiveButton(R.string.lbl_delete) { _, _ ->
                AlbumCollections.delete(name)
                onDeleted()
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }
}
