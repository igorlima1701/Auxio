/*
 * Copyright (c) 2026 Auxio Project
 * TagEditor.h is part of Auxio.
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

#ifndef AUXIO_TAGEDITOR_H
#define AUXIO_TAGEDITOR_H

#include <vector>

#include "taglib/tstring.h"

/*
 * Fork pessoal: edição cautelosa de tags.
 *
 * Formatos aceitos: MP3 (ID3v2, mantendo a versão 2.3 ou 2.4 que o arquivo já usa), FLAC, Ogg Opus e
 * Ogg Vorbis (comentários Vorbis). Qualquer outro formato é só leitura.
 *
 * A gravação é feita numa cópia do arquivo. Depois de gravar, a cópia é conferida contra o original:
 * o áudio tem de ser idêntico byte a byte, todas as tags não editadas têm de continuar iguais
 * (inclusive capas e quadros que o app não mostra) e os campos editados têm de ter exatamente os
 * novos valores. Se qualquer conferência falhar, a cópia é descartada e o original nunca é tocado.
 */
namespace tagedit {

    /* Chaves dos campos editáveis, no estilo do TagLib (PropertyMap). */
    extern const char *const KEYS[];
    extern const int KEY_COUNT;

    struct Field {
        TagLib::String key;
        TagLib::String value;
    };

    /*
     * Lê os campos editáveis. Valores com várias entradas vêm unidos por "; ".
     * Retorna false se o formato não for editável; nesse caso [format] traz o motivo.
     */
    bool readFields(const char *path, TagLib::String &format, std::vector<Field> &out);

    /*
     * Grava [edits] em [work] (uma cópia exata de [original]) e confere o resultado.
     * Retorna string vazia se tudo deu certo, ou a descrição do problema.
     */
    TagLib::String writeAndVerify(const char *original, const char *work,
            const std::vector<Field> &edits);

}

#endif //AUXIO_TAGEDITOR_H
