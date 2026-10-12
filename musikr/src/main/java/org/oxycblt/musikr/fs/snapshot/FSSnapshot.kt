/*
 * Copyright (c) 2026 Auxio Project
 * FSSnapshot.kt is part of Auxio.
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

package org.oxycblt.musikr.fs.snapshot

import android.content.Context
import android.net.Uri
import java.io.File as JFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.fs.AddedMs
import org.oxycblt.musikr.fs.Directory
import org.oxycblt.musikr.fs.FS
import org.oxycblt.musikr.fs.FSUpdate
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.util.tryAsyncWith

/**
 * Fork pessoal: "fotografia" da lista de arquivos de música da última varredura.
 *
 * Percorrer as pastas pelo Android (SAF) é a parte mais lenta da abertura do app. Com a fotografia,
 * o app monta a biblioteca na hora a partir da lista salva (e do cache de tags que já existe) e, em
 * seguida, faz a varredura de verdade em segundo plano para pegar arquivos novos, apagados ou
 * alterados.
 */
class FSSnapshot(context: Context) {
    private val file = JFile(context.filesDir, "fs_snapshot.tsv")

    /** Um [FS] que repassa os arquivos de [inner] e, ao final, salva a fotografia. */
    fun recording(inner: FS, sources: List<Location.Opened>): FS = RecordingFS(inner, sources)

    /** Um [FS] que devolve os arquivos da última fotografia, ou null se não houver uma válida. */
    suspend fun replay(sources: List<Location.Opened>): FS? =
        withContext(Dispatchers.IO) {
            try {
                load(sources)
            } catch (e: Exception) {
                null
            }
        }

    private fun sourcesKey(sources: List<Location.Opened>) =
        sources.joinToString("|") { it.uri.toString() }

    private fun load(sources: List<Location.Opened>): FS? {
        if (!file.exists()) return null
        val lines = file.readLines()
        if (lines.isEmpty() || lines[0] != "$VERSION\t${sourcesKey(sources)}") return null
        val directories = mutableListOf<Directory>()
        var current: Directory? = null
        var currentChildren = mutableListOf<File>()
        var currentDeferred = CompletableDeferred<Directory>()
        val files = mutableListOf<File>()

        fun finishDirectory() {
            val dir = current ?: return
            dir.children = currentChildren
            currentDeferred.complete(dir)
            directories += dir
        }

        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "D" -> {
                    if (parts.size != 4) return null
                    finishDirectory()
                    val source = sources.getOrNull(parts[1].toInt()) ?: return null
                    var path = source.path
                    for (segment in parts[3].split('/')) {
                        if (segment.isNotEmpty()) path = path.file(segment)
                    }
                    current = Directory(Uri.parse(parts[2]), path, null, emptyList())
                    currentChildren = mutableListOf()
                    currentDeferred = CompletableDeferred()
                }
                "F" -> {
                    if (parts.size != 6) return null
                    val dir = current ?: return null
                    val f =
                        File(
                            uri = Uri.parse(parts[1]),
                            path = dir.path.file(parts[2]),
                            addedMs = NoAddedMs,
                            modifiedMs = parts[5].toLong(),
                            mimeType = parts[3],
                            size = parts[4].toLong(),
                            parent = currentDeferred,
                        )
                        currentChildren += f
                        files += f
                }
                else -> return null
            }
        }
        finishDirectory()
        if (files.isEmpty()) return null
        return ReplayFS(files)
    }

    private fun save(sources: List<Location.Opened>, files: List<File>) {
        val byDirectory = linkedMapOf<Directory, MutableList<File>>()
        for (f in files) {
            @Suppress("OPT_IN_USAGE")
            val dir = f.parent?.takeIf { it.isCompleted }?.getCompleted() ?: return
            byDirectory.getOrPut(dir) { mutableListOf() } += f
        }
        val out = StringBuilder()
        out.append(VERSION).append('\t').append(sourcesKey(sources)).append('\n')
        for ((dir, children) in byDirectory) {
            val sourceIndex =
                sources.indexOfFirst {
                    it.path.volume == dir.path.volume &&
                        it.path.components.contains(dir.path.components)
                }
            if (sourceIndex < 0) return
            val relative =
                sources[sourceIndex].path.components.containing(dir.path.components).unixString
            val dirUri = dir.uri?.toString() ?: return
            if (!safe(dirUri) || !safe(relative)) return
            out.append("D\t").append(sourceIndex).append('\t').append(dirUri).append('\t')
            out.append(relative).append('\n')
            for (f in children) {
                val name = f.path.name ?: return
                val uri = f.uri.toString()
                if (!safe(name) || !safe(uri) || !safe(f.mimeType)) return
                out.append("F\t").append(uri).append('\t').append(name).append('\t')
                out.append(f.mimeType).append('\t').append(f.size).append('\t')
                out.append(f.modifiedMs).append('\n')
            }
        }
        val tmp = JFile(file.parentFile, "${file.name}.tmp")
        tmp.writeText(out.toString())
        if (!tmp.renameTo(file)) tmp.delete()
    }

    private fun safe(text: String) = '\t' !in text && '\n' !in text && '\r' !in text

    private inner class RecordingFS(
        private val inner: FS,
        private val sources: List<Location.Opened>,
    ) : FS {
        override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> =
            coroutineScope {
                tryAsyncWith(files, Dispatchers.IO) { out ->
                    val buffer = Channel<File>(Channel.UNLIMITED)
                    val task = inner.explore(buffer)
                    val recorded = mutableListOf<File>()
                    for (f in buffer) {
                        recorded += f
                        out.send(f)
                    }
                    task.await().getOrThrow()
                    try {
                        save(sources, recorded)
                    } catch (e: Exception) {
                        // A fotografia é só um atalho: se falhar, a abertura só não fica mais rápida.
                        file.delete()
                    }
                }
            }

        override fun track(): Flow<FSUpdate> = inner.track()
    }

    private class ReplayFS(private val snapshot: List<File>) : FS {
        override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> =
            coroutineScope {
                tryAsyncWith(files, Dispatchers.IO) { out ->
                    for (f in snapshot) out.send(f)
                }
            }

        override fun track(): Flow<FSUpdate> = emptyFlow()
    }

    private object NoAddedMs : AddedMs {
        override suspend fun resolve(): Long? = null
    }

    private companion object {
        const val VERSION = "auxio-fs-snapshot-1"
    }
}
