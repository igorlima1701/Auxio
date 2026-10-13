/*
 * Copyright (c) 2026 Auxio Project
 * LyricsFiles.kt is part of Auxio.
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

package org.oxycblt.auxio.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.tag.edit.TagEditor
import timber.log.Timber as L

/** Fork pessoal: de onde veio a letra mostrada. */
enum class LyricsSource {
    /** Arquivo .lrc ao lado da música. */
    FILE,
    /** Letra gravada dentro da própria música (USLT no MP3). */
    EMBEDDED,
    /** Não há letra. */
    NONE,
}

/** Fork pessoal: texto bruto da letra e de onde veio. */
data class LoadedLyrics(val text: String, val source: LyricsSource)

/**
 * Fork pessoal: acha, lê e grava o .lrc de uma música (mesmo nome, mesma pasta), usando a permissão
 * que o app já tem nas pastas de música. O MP3 nunca é alterado.
 */
object LyricsFiles {
    /** Muda a cada .lrc gravado, para o player recarregar a letra. */
    val version: StateFlow<Int>
        field = MutableStateFlow(0)

    private data class Place(val tree: Uri, val parentId: String, val lrcName: String)

    /** Lê a letra da música: primeiro o .lrc; se não houver, a letra embutida. Rodar fora da main. */
    fun load(context: Context, song: Song): LoadedLyrics {
        try {
            val place = locate(context, song)
            val lrc = place?.let { findChild(context, it) }
            if (lrc != null) {
                return LoadedLyrics(readText(context, lrc).first, LyricsSource.FILE)
            }
        } catch (e: Exception) {
            L.w(e, "Não consegui ler o .lrc")
        }
        val embedded =
            try {
                TagEditor(context).info(song.uri)["LYRICS"]
            } catch (e: Exception) {
                null
            }
        if (!embedded.isNullOrBlank()) return LoadedLyrics(embedded, LyricsSource.EMBEDDED)
        return LoadedLyrics("", LyricsSource.NONE)
    }

    /**
     * Grava [text] no .lrc da música, criando o arquivo se não existir. Mantém a quebra de linha que
     * o arquivo já usava (Windows ou não). Retorna null se deu certo, ou a descrição do problema.
     */
    fun save(context: Context, song: Song, text: String): String? {
        return try {
            val place = locate(context, song) ?: return "não achei a pasta da música com permissão de gravação"
            val resolver = context.contentResolver
            var target = findChild(context, place)
            var crlf = false
            if (target != null) {
                crlf = readText(context, target).second
            } else {
                // octet-stream impede o Android de acrescentar ".txt" ao nome.
                val parent = DocumentsContract.buildDocumentUriUsingTree(place.tree, place.parentId)
                target =
                    DocumentsContract.createDocument(
                        resolver,
                        parent,
                        "application/octet-stream",
                        place.lrcName,
                    ) ?: return "o Android não deixou criar o arquivo .lrc"
            }
            val normalized = text.replace("\r\n", "\n").trimEnd('\n') + "\n"
            val out = if (crlf) normalized.replace("\n", "\r\n") else normalized
            val bytes = out.toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
                ?: return "não consegui abrir o .lrc para gravar"
            // Confere o que ficou gravado.
            val check = readText(context, target).first
            if (check.replace("\r\n", "\n") != normalized) return "o arquivo gravado não confere"
            version.value = version.value + 1
            null
        } catch (e: Exception) {
            L.w(e, "Falha ao gravar .lrc")
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Descobre a pasta da música dentro de uma das pastas liberadas para o app. */
    private fun locate(context: Context, song: Song): Place? {
        val name = song.path.name ?: return null
        val base = name.substringBeforeLast('.', name)
        val lrcName = "$base.lrc"
        val uri = song.uri
        // Caso comum: a música já veio de uma pasta escolhida no app (URI de árvore).
        val fromTree =
            try {
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    val treeId = DocumentsContract.getTreeDocumentId(uri)
                    val docId = DocumentsContract.getDocumentId(uri)
                    val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, treeId)
                    Place(tree, parentOf(docId), lrcName)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        if (fromTree != null) return fromTree

        // Senão, monta o id do documento pelo caminho e procura uma pasta liberada que o contenha.
        val prefix =
            when (val volume = song.path.volume) {
                is Volume.Internal -> "primary"
                is Volume.External -> volume.id ?: return null
                else -> return null
            }
        val docId = "$prefix:${song.path.components}"
        for (perm in context.contentResolver.persistedUriPermissions) {
            if (!perm.isWritePermission) continue
            val treeId =
                try {
                    DocumentsContract.getTreeDocumentId(perm.uri)
                } catch (e: Exception) {
                    continue
                }
            val inside =
                if (treeId.endsWith(":")) docId.startsWith(treeId)
                else docId.startsWith("$treeId/")
            if (inside) return Place(perm.uri, parentOf(docId), lrcName)
        }
        return null
    }

    private fun parentOf(docId: String) =
        if (docId.contains('/')) docId.substringBeforeLast('/') else docId.substringBefore(':') + ":"

    /** Procura o .lrc na pasta, sem diferenciar maiúsculas (".LRC" também vale). */
    private fun findChild(context: Context, place: Place): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(place.tree, place.parentId)
        context.contentResolver
            .query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val display = cursor.getString(1) ?: continue
                    if (display.equals(place.lrcName, ignoreCase = true)) {
                        return DocumentsContract.buildDocumentUriUsingTree(
                            place.tree,
                            cursor.getString(0),
                        )
                    }
                }
            }
        return null
    }

    /** Lê o texto detectando a codificação (UTF-8, UTF-16 ou Windows-1252). Também diz se usa CRLF. */
    private fun readText(context: Context, uri: Uri): Pair<String, Boolean> {
        val bytes =
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val text =
            when {
                bytes.size >= 3 &&
                    bytes[0] == 0xEF.toByte() &&
                    bytes[1] == 0xBB.toByte() &&
                    bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                else ->
                    try {
                        Charsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString()
                    } catch (e: CharacterCodingException) {
                        String(bytes, Charset.forName("windows-1252"))
                    }
            }
        return text to text.contains("\r\n")
    }
}
