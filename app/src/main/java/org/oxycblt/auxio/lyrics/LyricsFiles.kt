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
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
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

/**
 * Fork pessoal: texto bruto da letra e de onde veio. [detail] explica o que foi procurado quando não
 * há letra; [needsAccess] = o app não tem permissão para ver a pasta da música.
 */
data class LoadedLyrics(
    val text: String,
    val source: LyricsSource,
    val fileName: String? = null,
    val detail: String? = null,
    val needsAccess: Boolean = false,
)

/**
 * Fork pessoal: acha, lê e grava o .lrc de uma música, usando a permissão que o app tem nas pastas de
 * música. O MP3 nunca é alterado.
 *
 * O .lrc vale se, na mesma pasta, tiver o mesmo nome da música; ou o mesmo nome sem o número da faixa
 * na frente; ou o nome igual ao título, ou "Artista - Título". Maiúsculas, acentos compostos e tipos
 * de apóstrofo não fazem diferença.
 */
object LyricsFiles {
    /** Muda a cada .lrc gravado ou a cada pasta liberada, para o player recarregar a letra. */
    val version: StateFlow<Int>
        field = MutableStateFlow(0)

    fun bump() {
        version.value = version.value + 1
    }

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    private data class Place(val tree: Uri, val parentId: String, val lrcName: String)

    /** Lê a letra da música: primeiro o .lrc; se não houver, a letra embutida. Rodar fora da main. */
    fun load(context: Context, song: Song): LoadedLyrics {
        var detail: String? = null
        var needsAccess = false
        val folder = song.path.directory.components.toString().ifEmpty { "/" }
        try {
            val place = locate(context, song)
            if (place != null) {
                val children = listChildren(context, place)
                val match = bestMatch(children.map { it.second }, song)
                if (match != null) {
                    val uri = children.first { it.second == match }.first
                    return LoadedLyrics(readText(context, uri).first, LyricsSource.FILE, match)
                }
                val lrcCount = children.count { it.second.endsWith(".lrc", ignoreCase = true) }
                detail =
                    if (lrcCount == 0) "Nenhum .lrc na pasta $folder"
                    else "Nenhum dos $lrcCount .lrc da pasta $folder tem o nome desta música"
            } else {
                // Sem pasta liberada: tenta o caminho direto (só funciona com acesso a todos os
                // arquivos ou em Androids antigos).
                val dir = directFolder(song)
                val names = dir?.list()?.toList()
                if (names != null) {
                    val match = bestMatch(names, song)
                    if (match != null) {
                        val bytes = File(dir, match).readBytes()
                        return LoadedLyrics(decode(bytes), LyricsSource.FILE, match)
                    }
                    detail = "Nenhum .lrc correspondente na pasta $folder"
                } else {
                    needsAccess = true
                    detail = "O app não tem acesso à pasta $folder"
                }
            }
        } catch (e: Exception) {
            L.w(e, "Não consegui ler o .lrc")
            detail = "Erro ao procurar o .lrc: ${e.message ?: e.javaClass.simpleName}"
        }
        val embedded =
            try {
                TagEditor(context).info(song.uri)["LYRICS"]
            } catch (e: Exception) {
                null
            }
        if (!embedded.isNullOrBlank()) return LoadedLyrics(embedded, LyricsSource.EMBEDDED)
        return LoadedLyrics("", LyricsSource.NONE, null, detail, needsAccess)
    }

    /**
     * Grava [text] no .lrc da música (o que já existe ou um novo com o nome da música). Mantém a
     * quebra de linha que o arquivo já usava. Retorna null se deu certo, ou a descrição do problema.
     */
    fun save(context: Context, song: Song, text: String): String? {
        return try {
            val place =
                locate(context, song)
                    ?: return "o app não tem acesso à pasta da música; libere a pasta na tela da letra"
            val resolver = context.contentResolver
            val children = listChildren(context, place)
            val match = bestMatch(children.map { it.second }, song)
            var target = match?.let { m -> children.first { it.second == m }.first }
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
            bump()
            null
        } catch (e: Exception) {
            L.w(e, "Falha ao gravar .lrc")
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Endereço da pasta da música no seletor de pastas do Android (para pedir acesso a ela). */
    fun folderHint(song: Song): Uri? {
        val docId = documentId(song) ?: return null
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, parentOf(docId))
    }

    /** Id do documento no armazenamento do Android, montado pelo caminho da música. */
    private fun documentId(song: Song): String? {
        val prefix =
            when (val volume = song.path.volume) {
                is Volume.Internal -> "primary"
                is Volume.External -> volume.id ?: return null
                else -> return null
            }
        return "$prefix:${song.path.components}"
    }

    /** Descobre a pasta da música dentro de uma das pastas liberadas para o app. */
    private fun locate(context: Context, song: Song): Place? {
        val name = song.path.name ?: return null
        val base = name.substringBeforeLast('.', name)
        val lrcName = "$base.lrc"
        val uri = song.uri
        // Caso comum: a música veio de uma pasta escolhida no app (content://…/tree/X/document/Y).
        val segments = uri.pathSegments
        if (segments.size == 4 && segments[0] == "tree" && segments[2] == "document") {
            val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, segments[1])
            return Place(tree, parentOf(segments[3]), lrcName)
        }

        // Senão (músicas carregadas pelo MediaStore), procura uma pasta liberada que contenha a música.
        val docId = documentId(song) ?: return null
        for (perm in context.contentResolver.persistedUriPermissions) {
            if (!perm.isReadPermission) continue
            if (perm.uri.authority != EXTERNAL_STORAGE) continue
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

    /** Pasta da música como arquivo comum, se o app conseguir listá-la. */
    private fun directFolder(song: Song): File? {
        val root = song.path.volume.components?.toString() ?: return null
        val dir = File("/$root/${song.path.directory.components}")
        return dir.takeIf { it.canRead() }
    }

    /** Arquivos da pasta: (uri, nome). */
    private fun listChildren(context: Context, place: Place): List<Pair<Uri, String>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(place.tree, place.parentId)
        val out = mutableListOf<Pair<Uri, String>>()
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
                    val id = cursor.getString(0) ?: continue
                    val display = cursor.getString(1) ?: continue
                    out += DocumentsContract.buildDocumentUriUsingTree(place.tree, id) to display
                }
            }
        return out
    }

    /** Escolhe, entre os nomes da pasta, o .lrc desta música (ou null). */
    private fun bestMatch(names: List<String>, song: Song): String? {
        val lrcs = names.filter { it.endsWith(".lrc", ignoreCase = true) }
        if (lrcs.isEmpty()) return null
        val fileBase = song.path.name?.substringBeforeLast('.') ?: return null
        val title = (song.name as? org.oxycblt.musikr.tag.Name.Known)?.raw
        val artist = (song.artists.firstOrNull()?.name as? org.oxycblt.musikr.tag.Name.Known)?.raw
        val keys =
            listOfNotNull(
                normalize(fileBase),
                normalize(stripTrack(fileBase)),
                title?.let(::normalize),
                if (title != null && artist != null) normalize("$artist - $title") else null,
            )
        for (key in keys) {
            if (key.isEmpty()) continue
            lrcs.firstOrNull { normalize(it.dropLast(4)) == key }?.let { return it }
        }
        // Por último: o .lrc sem o número da faixa igual à música sem o número da faixa.
        val bare = normalize(stripTrack(fileBase))
        return lrcs.firstOrNull { bare.isNotEmpty() && normalize(stripTrack(it.dropLast(4))) == bare }
    }

    private val TRACK_PREFIX = Regex("""^\s*\d{1,3}(\s*[-._)]\s*|\s+)""")

    private fun stripTrack(s: String) = s.replace(TRACK_PREFIX, "")

    private fun normalize(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFC)
            .lowercase()
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('`', '\'')
            .replace('´', '\'')
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Lê o texto detectando a codificação (UTF-8, UTF-16 ou Windows-1252). Também diz se usa CRLF. */
    private fun readText(context: Context, uri: Uri): Pair<String, Boolean> {
        val bytes =
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val text = decode(bytes)
        return text to text.contains("\r\n")
    }

    private fun decode(bytes: ByteArray): String =
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
}
