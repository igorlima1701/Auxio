/*
 * Copyright (c) 2026 Auxio Project
 * TagEditor.kt is part of Auxio.
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

package org.oxycblt.musikr.tag.edit

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.oxycblt.musikr.util.fixWindows1252

/** Campos editáveis, na mesma ordem e com as mesmas chaves do código nativo. */
enum class TagField(val key: String) {
    TITLE("TITLE"),
    ARTIST("ARTIST"),
    ALBUM_ARTIST("ALBUMARTIST"),
    ALBUM("ALBUM"),
    DATE("DATE"),
    ORIGINAL_DATE("ORIGINALDATE"),
    TRACK("TRACKNUMBER"),
    DISC("DISCNUMBER"),
    GENRE("GENRE"),
    COMMENT("COMMENT");

    companion object {
        fun fromKey(key: String) = entries.find { it.key == key }
    }
}

/** Tags editáveis lidas de um arquivo. Valores com várias entradas vêm unidos por "; ". */
data class EditableTags(val format: String, val values: Map<TagField, String>)

sealed interface TagReadResult {
    data class Ok(val tags: EditableTags) : TagReadResult

    data class Unsupported(val reason: String) : TagReadResult
}

/**
 * Fork pessoal: edição cautelosa de tags (MP3, FLAC, Opus e Ogg Vorbis).
 *
 * Passo a passo de cada gravação:
 * 1. Copia o arquivo original para uma pasta temporária do app ("original") e faz outra cópia
 *    ("rascunho").
 * 2. Grava só os campos alterados no rascunho, mantendo o formato de tag que o arquivo já usa.
 * 3. Confere o rascunho contra a cópia original: áudio idêntico byte a byte, capas e demais tags
 *    iguais e campos editados com exatamente os valores digitados.
 * 4. Confere se o arquivo no aparelho não mudou desde o passo 1.
 * 5. Só então substitui o arquivo e relê o que foi gravado para confirmar. Se essa confirmação
 *    falhar, o conteúdo original é regravado.
 *
 * Se qualquer passo falhar, o arquivo no aparelho continua como estava.
 */
class TagEditor(private val context: Context) {
    private val workDir = File(context.cacheDir, "tag_edit")

    /** [fileName] é usado só para reconhecer o formato pela extensão. */
    fun read(uri: Uri, fileName: String): TagReadResult {
        val original = copyToTemp(uri, "read", fileName) ?: return TagReadResult.Unsupported(ERR_OPEN)
        try {
            val raw = TagEditorJNI.read(original.path)
            val first = raw.firstOrNull() ?: return TagReadResult.Unsupported(ERR_OPEN)
            if (first.startsWith("!")) return TagReadResult.Unsupported(first.drop(1))
            val values = mutableMapOf<TagField, String>()
            var i = 1
            while (i + 1 < raw.size) {
                TagField.fromKey(raw[i])?.let { values[it] = raw[i + 1].fixWindows1252() }
                i += 2
            }
            return TagReadResult.Ok(EditableTags(first, values))
        } finally {
            original.delete()
        }
    }

    /**
     * Grava [edits] no arquivo [uri]. Retorna null se deu certo, ou a descrição do problema (e nesse
     * caso o arquivo não foi alterado).
     */
    fun write(uri: Uri, fileName: String, edits: Map<TagField, String>): String? {
        if (edits.isEmpty()) return null
        val original = copyToTemp(uri, "orig", fileName) ?: return ERR_OPEN
        val work = File(workDir, "work_${System.nanoTime()}_${original.name.substringAfter('_')}")
        try {
            original.copyTo(work, overwrite = true)
            val keys = edits.keys.map { it.key }.toTypedArray()
            val values = edits.keys.map { edits.getValue(it) }.toTypedArray()
            val error = TagEditorJNI.write(original.path, work.path, keys, values)
            if (error.isNotEmpty()) return error

            // O arquivo pode ter sido mudado por outro app enquanto isso.
            val originalHash = sha256(original.inputStream())
            val currentHash = openInput(uri)?.let(::sha256) ?: return ERR_OPEN
            if (!originalHash.contentEquals(currentHash)) {
                return "o arquivo foi alterado por outro app durante a edição; nada foi gravado"
            }

            val workHash = sha256(work.inputStream())
            if (!replace(uri, work)) {
                return "não foi possível gravar no arquivo (sem permissão de escrita?)"
            }
            val writtenHash = openInput(uri)?.let(::sha256)
            if (writtenHash == null || !writtenHash.contentEquals(workHash)) {
                // Algo deu errado na substituição: regrava o conteúdo original.
                val restored =
                    replace(uri, original) &&
                        openInput(uri)?.let(::sha256)?.contentEquals(originalHash) == true
                return if (restored) {
                    "a gravação não pôde ser confirmada; o arquivo original foi restaurado"
                } else {
                    "a gravação não pôde ser confirmada e a restauração falhou; " +
                        "uma cópia do original ficou em ${keepBackup(original)}"
                }
            }
            return null
        } catch (e: Exception) {
            return e.message ?: e.toString()
        } finally {
            original.delete()
            work.delete()
        }
    }

    private fun copyToTemp(uri: Uri, prefix: String, fileName: String): File? {
        workDir.mkdirs()
        val ext = fileName.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
        val file = File(workDir, "${prefix}_${System.nanoTime()}.${ext.ifEmpty { "bin" }}")
        val input = openInput(uri) ?: return null
        input.use { inp -> file.outputStream().use { inp.copyTo(it) } }
        return file
    }

    private fun openInput(uri: Uri): InputStream? =
        try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }

    private fun replace(uri: Uri, source: File): Boolean =
        try {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: return false
            out.use { o -> source.inputStream().use { it.copyTo(o) } }
            true
        } catch (e: Exception) {
            false
        }

    private fun keepBackup(original: File): String {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "backup_tags")
        dir.mkdirs()
        val target = File(dir, original.name)
        original.copyTo(target, overwrite = true)
        return target.path
    }

    private fun sha256(input: InputStream): ByteArray =
        input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            digest.digest()
        }

    private companion object {
        const val ERR_OPEN = "não foi possível abrir o arquivo"
    }
}

internal object TagEditorJNI {
    init {
        System.loadLibrary("tagJNI")
    }

    fun read(path: String): Array<String> = readNative(path)

    fun write(original: String, work: String, keys: Array<String>, values: Array<String>): String =
        writeNative(original, work, keys, values)

    private external fun readNative(path: String): Array<String>

    private external fun writeNative(
        original: String,
        work: String,
        keys: Array<String>,
        values: Array<String>,
    ): String
}
