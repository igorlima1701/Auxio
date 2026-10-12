/*
 * Copyright (c) 2026 Auxio Project
 * TagEditor.cpp is part of Auxio.
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

#include "TagEditor.h"

#include <algorithm>
#include <cstdio>
#include <map>
#include <memory>
#include <string>

#include "taglib/fileref.h"
#include "taglib/tfile.h"
#include "taglib/tpropertymap.h"
#include "taglib/tvariant.h"
#include "taglib/mpegfile.h"
#include "taglib/id3v1tag.h"
#include "taglib/id3v2tag.h"
#include "taglib/id3v2header.h"
#include "taglib/id3v2frame.h"
#include "taglib/textidentificationframe.h"
#include "taglib/commentsframe.h"
#include "taglib/flacfile.h"
#include "taglib/opusfile.h"
#include "taglib/vorbisfile.h"
#include "taglib/xiphcomment.h"

using TagLib::ByteVector;
using TagLib::String;
using TagLib::StringList;

namespace tagedit {

    const char *const KEYS[] = {"TITLE", "ARTIST", "ALBUMARTIST", "ALBUM", "DATE",
                                "ORIGINALDATE", "TRACKNUMBER", "DISCNUMBER", "GENRE", "COMMENT",
                                "ALBUMRATING"};
    const int KEY_COUNT = sizeof(KEYS) / sizeof(KEYS[0]);

    namespace {

        const String SEPARATOR("; ");

        String U(const char *s) { return String(s, String::UTF8); }

        enum class Kind { Unsupported, Mp3, Flac, Opus, Vorbis };

        // ---------- Utilidades ----------

        String join(const StringList &values) {
            return values.toString(SEPARATOR);
        }

        // Só divide em vários valores se o campo original já tinha vários valores.
        StringList toValues(const String &text, bool wasMultiValue) {
            StringList out;
            String trimmed = text.stripWhiteSpace();
            if (trimmed.isEmpty()) return out;
            if (!wasMultiValue) {
                out.append(trimmed);
                return out;
            }
            for (const auto &part: trimmed.split(SEPARATOR)) {
                String p = part.stripWhiteSpace();
                if (!p.isEmpty()) out.append(p);
            }
            return out;
        }

        bool isLatin1(const StringList &values) {
            return std::all_of(values.begin(), values.end(),
                    [](const String &s) { return s.isLatin1(); });
        }

        Kind kindOf(TagLib::File *file) {
            if (dynamic_cast<TagLib::MPEG::File *>(file)) return Kind::Mp3;
            if (dynamic_cast<TagLib::FLAC::File *>(file)) return Kind::Flac;
            if (dynamic_cast<TagLib::Ogg::Opus::File *>(file)) return Kind::Opus;
            if (dynamic_cast<TagLib::Ogg::Vorbis::File *>(file)) return Kind::Vorbis;
            return Kind::Unsupported;
        }

        // Nomes alternativos que outros programas usam nos comentários Vorbis.
        std::vector<String> xiphCandidates(const String &key) {
            if (key == "ALBUMARTIST") return {"ALBUMARTIST", "ALBUM ARTIST"};
            if (key == "DATE") return {"DATE", "YEAR"};
            if (key == "COMMENT") return {"COMMENT", "DESCRIPTION"};
            return {key};
        }

        ByteVector id3FrameFor(const String &key) {
            if (key == "TITLE") return "TIT2";
            if (key == "ARTIST") return "TPE1";
            if (key == "ALBUMARTIST") return "TPE2";
            if (key == "ALBUM") return "TALB";
            if (key == "DATE") return "TDRC";
            if (key == "ORIGINALDATE") return "TDOR";
            if (key == "TRACKNUMBER") return "TRCK";
            if (key == "DISCNUMBER") return "TPOS";
            if (key == "GENRE") return "TCON";
            return ByteVector(); // COMMENT é tratado à parte (quadro COMM)
        }

        // Valores atuais de um campo, procurando também nos nomes alternativos.
        StringList currentValues(const TagLib::PropertyMap &props, const String &key) {
            for (const auto &k: xiphCandidates(key)) {
                auto it = props.find(k);
                if (it != props.end() && !it->second.isEmpty()) return it->second;
            }
            return {};
        }

        // ---------- Gravação: ID3v2 ----------

        String::Type encodingFor(TagLib::ID3v2::Frame *existing, const StringList &values,
                TagLib::ID3v2::Version version) {
            String::Type enc = String::Latin1;
            if (auto *text = dynamic_cast<TagLib::ID3v2::TextIdentificationFrame *>(existing)) {
                enc = text->textEncoding();
            } else if (auto *comm = dynamic_cast<TagLib::ID3v2::CommentsFrame *>(existing)) {
                enc = comm->textEncoding();
            }
            if (enc == String::Latin1 && !isLatin1(values)) {
                enc = version == TagLib::ID3v2::v3 ? String::UTF16 : String::UTF8;
            }
            if (version == TagLib::ID3v2::v3 && (enc == String::UTF8 || enc == String::UTF16BE)) {
                enc = String::UTF16;
            }
            return enc;
        }

        void setId3Text(TagLib::ID3v2::Tag *tag, const ByteVector &id, const StringList &values,
                TagLib::ID3v2::Version version) {
            const auto &existing = tag->frameList(id);
            String::Type enc = encodingFor(existing.isEmpty() ? nullptr : existing.front(),
                    values, version);
            tag->removeFrames(id);
            if (values.isEmpty()) return;
            auto *frame = new TagLib::ID3v2::TextIdentificationFrame(id, enc);
            frame->setText(values);
            tag->addFrame(frame);
        }

        // Quadro TXXX cuja descrição é o próprio nome do campo (ex.: TXXX:COMMENT). O TagLib mostra
        // esses quadros como parte do campo, então eles são substituídos junto com ele.
        bool isTxxxFor(TagLib::ID3v2::Frame *frame, const String &key) {
            auto *txxx = dynamic_cast<TagLib::ID3v2::UserTextIdentificationFrame *>(frame);
            if (txxx == nullptr) return false;
            String desc = txxx->description().upper();
            for (const auto &k: xiphCandidates(key)) {
                if (desc == k) return true;
            }
            return false;
        }

        void removeTxxxFor(TagLib::ID3v2::Tag *tag, const String &key) {
            std::vector<TagLib::ID3v2::Frame *> toRemove;
            for (auto *frame: tag->frameList("TXXX")) {
                if (isTxxxFor(frame, key)) toRemove.push_back(frame);
            }
            for (auto *frame: toRemove) tag->removeFrame(frame, true);
        }

        // Só mexe no comentário principal (sem descrição); comentários técnicos ficam intactos.
        void setId3Comment(TagLib::ID3v2::Tag *tag, const String &value,
                TagLib::ID3v2::Version version) {
            ByteVector language("eng");
            TagLib::ID3v2::Frame *first = nullptr;
            std::vector<TagLib::ID3v2::Frame *> toRemove;
            for (auto *frame: tag->frameList("COMM")) {
                auto *comm = dynamic_cast<TagLib::ID3v2::CommentsFrame *>(frame);
                if (comm != nullptr && comm->description().isEmpty()) {
                    if (first == nullptr) {
                        first = comm;
                        if (comm->language().size() == 3) language = comm->language();
                    }
                    toRemove.push_back(frame);
                }
            }
            StringList values;
            if (!value.stripWhiteSpace().isEmpty()) values.append(value);
            String::Type enc = encodingFor(first, values, version);
            for (auto *frame: toRemove) tag->removeFrame(frame, true);
            if (values.isEmpty()) return;
            auto *comm = new TagLib::ID3v2::CommentsFrame(enc);
            comm->setLanguage(language);
            comm->setText(value);
            tag->addFrame(comm);
        }

        int leadingNumber(const String &s) {
            std::string str = s.to8Bit(true);
            int n = 0;
            bool any = false;
            for (char c: str) {
                if (c < '0' || c > '9') break;
                n = n * 10 + (c - '0');
                any = true;
            }
            return any ? n : 0;
        }

        // O ID3v1 só aceita Latin-1: caracteres fora dele viram "?" (como faz o Mp3tag), em vez de
        // o campo ficar vazio.
        String latin1Safe(const String &value) {
            std::wstring w = value.toWString();
            for (auto &c: w) {
                if (static_cast<unsigned long>(c) > 0xFF) c = L'?';
            }
            return String(w);
        }

        // Mantém o ID3v1 (se o arquivo já tiver) coerente com o ID3v2.
        void syncId3v1(TagLib::ID3v1::Tag *v1, const String &key, const String &rawValue) {
            String value = latin1Safe(rawValue);
            if (key == "TITLE") v1->setTitle(value);
            else if (key == "ARTIST") v1->setArtist(value);
            else if (key == "ALBUM") v1->setAlbum(value);
            else if (key == "DATE") v1->setYear(leadingNumber(value));
            else if (key == "COMMENT") v1->setComment(value);
            else if (key == "TRACKNUMBER") v1->setTrack(leadingNumber(value));
            else if (key == "GENRE") v1->setGenre(value);
        }

        String writeMp3(TagLib::MPEG::File *file, const std::vector<Field> &edits,
                const TagLib::PropertyMap &before) {
            bool hadId3v2 = file->hasID3v2Tag();
            bool hadId3v1 = file->hasID3v1Tag();
            bool hadApe = file->hasAPETag();
            auto *tag = file->ID3v2Tag(true);
            TagLib::ID3v2::Version version = TagLib::ID3v2::v3;
            if (hadId3v2 && tag->header()->majorVersion() == 4) version = TagLib::ID3v2::v4;

            for (const auto &edit: edits) {
                bool multi = currentValues(before, edit.key).size() > 1;
                StringList values = toValues(edit.value, multi);
                removeTxxxFor(tag, edit.key);
                if (edit.key == "COMMENT") {
                    setId3Comment(tag, edit.value.stripWhiteSpace(), version);
                } else if (edit.key == "ALBUMRATING") {
                    // Campo personalizado: quadro TXXX "ALBUMRATING" (o antigo já foi removido).
                    if (!values.isEmpty()) {
                        auto *txxx = new TagLib::ID3v2::UserTextIdentificationFrame(
                                encodingFor(nullptr, values, version));
                        txxx->setDescription("ALBUMRATING");
                        txxx->setText(values);
                        tag->addFrame(txxx);
                    }
                } else {
                    ByteVector id = id3FrameFor(edit.key);
                    if (id.isEmpty()) return "campo desconhecido: " + edit.key;
                    setId3Text(tag, id, values, version);
                }
                if (hadId3v1) syncId3v1(file->ID3v1Tag(false), edit.key, join(values));
            }

            int tags = TagLib::MPEG::File::ID3v2;
            if (hadId3v1) tags |= TagLib::MPEG::File::ID3v1;
            if (hadApe) tags |= TagLib::MPEG::File::APE;
            if (!file->save(tags, TagLib::File::StripNone, version,
                    TagLib::File::DoNotDuplicate)) {
                return U("não foi possível gravar o arquivo");
            }
            return "";
        }

        // ---------- Gravação: comentários Vorbis (FLAC, Opus, Vorbis) ----------

        void setXiph(TagLib::Ogg::XiphComment *xc, const String &key, const StringList &values) {
            std::vector<String> targets;
            for (const auto &k: xiphCandidates(key)) {
                if (xc->contains(k)) targets.push_back(k);
            }
            if (targets.empty()) targets.push_back(key);
            for (const auto &k: targets) {
                xc->removeFields(k);
                for (const auto &v: values) xc->addField(k, v, false);
            }
        }

        String writeXiph(TagLib::File *file, TagLib::Ogg::XiphComment *xc,
                const std::vector<Field> &edits, const TagLib::PropertyMap &before) {
            if (xc == nullptr) return U("o arquivo não tem comentários Vorbis");
            for (const auto &edit: edits) {
                bool multi = currentValues(before, edit.key).size() > 1;
                setXiph(xc, edit.key, toValues(edit.value, multi));
            }
            if (!file->save()) return U("não foi possível gravar o arquivo");
            return "";
        }

        // ---------- Conferência do áudio ----------

        struct RawFile {
            std::vector<unsigned char> data;
            bool load(const char *path) {
                FILE *f = std::fopen(path, "rb");
                if (f == nullptr) return false;
                std::fseek(f, 0, SEEK_END);
                long size = std::ftell(f);
                std::fseek(f, 0, SEEK_SET);
                if (size < 0) {
                    std::fclose(f);
                    return false;
                }
                data.resize(static_cast<size_t>(size));
                size_t read = data.empty() ? 0 : std::fread(data.data(), 1, data.size(), f);
                std::fclose(f);
                return read == data.size();
            }
        };

        size_t skipId3v2(const std::vector<unsigned char> &d, size_t pos) {
            while (pos + 10 <= d.size() && d[pos] == 'I' && d[pos + 1] == 'D' && d[pos + 2] == '3') {
                size_t size = (size_t(d[pos + 6] & 0x7F) << 21) | (size_t(d[pos + 7] & 0x7F) << 14) |
                              (size_t(d[pos + 8] & 0x7F) << 7) | size_t(d[pos + 9] & 0x7F);
                size_t total = 10 + size + ((d[pos + 5] & 0x10) ? 10 : 0);
                pos += total;
            }
            return pos;
        }

        size_t trimTrailingTags(const std::vector<unsigned char> &d, size_t start) {
            size_t end = d.size();
            if (end >= start + 128 && d[end - 128] == 'T' && d[end - 127] == 'A' && d[end - 126] == 'G') {
                end -= 128;
            }
            if (end >= start + 32 && std::equal(d.begin() + (end - 32), d.begin() + (end - 24),
                    reinterpret_cast<const unsigned char *>("APETAGEX"))) {
                size_t size = size_t(d[end - 20]) | (size_t(d[end - 19]) << 8) |
                              (size_t(d[end - 18]) << 16) | (size_t(d[end - 17]) << 24);
                bool hasHeader = (d[end - 9] & 0x80) != 0;
                size_t total = size + (hasHeader ? 32 : 0);
                if (total <= end - start) end -= total;
            }
            return end;
        }

        bool sameRange(const RawFile &a, size_t a0, size_t a1, const RawFile &b, size_t b0,
                size_t b1) {
            if (a1 < a0 || b1 < b0 || a1 - a0 != b1 - b0) return false;
            return std::equal(a.data.begin() + a0, a.data.begin() + a1, b.data.begin() + b0);
        }

        bool flacAudio(const RawFile &f, size_t &start, size_t &end, size_t &infoStart) {
            const auto &d = f.data;
            size_t pos = skipId3v2(d, 0);
            if (pos + 4 > d.size() || d[pos] != 'f' || d[pos + 1] != 'L' || d[pos + 2] != 'a' ||
                d[pos + 3] != 'C') {
                return false;
            }
            pos += 4;
            infoStart = pos;
            bool last = false;
            while (!last) {
                if (pos + 4 > d.size()) return false;
                last = (d[pos] & 0x80) != 0;
                size_t len = (size_t(d[pos + 1]) << 16) | (size_t(d[pos + 2]) << 8) | size_t(d[pos + 3]);
                pos += 4 + len;
            }
            start = pos;
            end = trimTrailingTags(d, start);
            return start <= end;
        }

        // Pacotes de um fluxo Ogg (assume um único fluxo lógico, como em arquivos de música).
        bool oggPackets(const RawFile &f, std::vector<std::vector<unsigned char>> &packets) {
            const auto &d = f.data;
            size_t pos = 0;
            std::vector<unsigned char> current;
            while (pos + 27 <= d.size()) {
                if (d[pos] != 'O' || d[pos + 1] != 'g' || d[pos + 2] != 'g' || d[pos + 3] != 'S') {
                    return false;
                }
                size_t segments = d[pos + 26];
                if (pos + 27 + segments > d.size()) return false;
                size_t body = pos + 27 + segments;
                for (size_t i = 0; i < segments; i++) {
                    size_t lace = d[pos + 27 + i];
                    if (body + lace > d.size()) return false;
                    current.insert(current.end(), d.begin() + body, d.begin() + body + lace);
                    body += lace;
                    if (lace < 255) {
                        packets.push_back(std::move(current));
                        current.clear();
                    }
                }
                pos = body;
            }
            if (!current.empty()) packets.push_back(std::move(current));
            return pos == d.size();
        }

        String verifyAudio(Kind kind, const char *original, const char *work) {
            RawFile a, b;
            if (!a.load(original) || !b.load(work)) return U("não foi possível reler os arquivos");
            switch (kind) {
                case Kind::Mp3: {
                    size_t a0 = skipId3v2(a.data, 0), b0 = skipId3v2(b.data, 0);
                    size_t a1 = trimTrailingTags(a.data, a0), b1 = trimTrailingTags(b.data, b0);
                    if (!sameRange(a, a0, a1, b, b0, b1)) return U("o áudio do MP3 mudaria");
                    return "";
                }
                case Kind::Flac: {
                    size_t a0, a1, ai, b0, b1, bi;
                    if (!flacAudio(a, a0, a1, ai) || !flacAudio(b, b0, b1, bi)) {
                        return U("não foi possível localizar o áudio do FLAC");
                    }
                    // Bloco STREAMINFO (contém a soma MD5 do áudio) tem de ser idêntico.
                    if (!sameRange(a, ai, ai + 38, b, bi, bi + 38)) return "o STREAMINFO do FLAC mudaria";
                    if (!sameRange(a, a0, a1, b, b0, b1)) return U("o áudio do FLAC mudaria");
                    return "";
                }
                case Kind::Opus:
                case Kind::Vorbis: {
                    std::vector<std::vector<unsigned char>> pa, pb;
                    if (!oggPackets(a, pa) || !oggPackets(b, pb)) return U("não foi possível ler o Ogg");
                    if (pa.size() != pb.size() || pa.size() < 2) return U("o número de pacotes Ogg mudaria");
                    for (size_t i = 0; i < pa.size(); i++) {
                        if (i == 1) continue; // pacote de comentários (as tags)
                        if (pa[i] != pb[i]) return U("o áudio do Ogg mudaria");
                    }
                    return "";
                }
                default:
                    return U("formato não suportado");
            }
        }

        // ---------- Conferência das tags ----------

        bool samePictures(TagLib::File *a, TagLib::File *b) {
            auto pa = a->complexProperties("PICTURE");
            auto pb = b->complexProperties("PICTURE");
            if (pa.size() != pb.size()) return false;
            auto ia = pa.begin();
            auto ib = pb.begin();
            for (; ia != pa.end(); ++ia, ++ib) {
                if (ia->value("data").toByteVector() != ib->value("data").toByteVector()) return false;
                if (ia->value("mimeType").toString() != ib->value("mimeType").toString()) return false;
                if (ia->value("pictureType").toString() != ib->value("pictureType").toString()) return false;
                if (ia->value("description").toString() != ib->value("description").toString()) return false;
            }
            return true;
        }

        // Todos os quadros ID3v2 que não foram editados têm de continuar idênticos.
        bool sameOtherId3Frames(TagLib::MPEG::File *a, TagLib::MPEG::File *b,
                const std::vector<ByteVector> &edited, bool commentEdited,
                const std::vector<Field> &edits) {
            auto collect = [&](TagLib::MPEG::File *f) {
                std::vector<ByteVector> out;
                auto *tag = f->ID3v2Tag(false);
                if (tag == nullptr) return out;
                for (auto *frame: tag->frameList()) {
                    ByteVector id = frame->frameID();
                    if (std::find(edited.begin(), edited.end(), id) != edited.end()) continue;
                    bool editedTxxx = false;
                    for (const auto &e: edits) editedTxxx = editedTxxx || isTxxxFor(frame, e.key);
                    if (editedTxxx) continue;
                    if (id == "COMM" && commentEdited) {
                        auto *comm = dynamic_cast<TagLib::ID3v2::CommentsFrame *>(frame);
                        if (comm != nullptr && comm->description().isEmpty()) continue;
                    }
                    out.push_back(id + frame->render());
                }
                std::sort(out.begin(), out.end());
                return out;
            };
            return collect(a) == collect(b);
        }

        bool sameAudioProperties(TagLib::File *a, TagLib::File *b) {
            auto *pa = a->audioProperties();
            auto *pb = b->audioProperties();
            if (pa == nullptr || pb == nullptr) return pa == pb;
            // O bitrate não entra: em Ogg ele é estimado pelo tamanho do arquivo, que muda com as
            // tags. O áudio em si já foi comparado byte a byte.
            return pa->lengthInMilliseconds() == pb->lengthInMilliseconds() &&
                   pa->sampleRate() == pb->sampleRate() && pa->channels() == pb->channels();
        }

        String verifyTags(Kind kind, const char *original, const char *work,
                const std::vector<Field> &edits, const TagLib::PropertyMap &before) {
            TagLib::FileRef ra(original, true, TagLib::AudioProperties::Average);
            TagLib::FileRef rb(work, true, TagLib::AudioProperties::Average);
            if (ra.isNull() || rb.isNull()) return U("não foi possível reler as tags");
            TagLib::File *fa = ra.file();
            TagLib::File *fb = rb.file();
            if (!sameAudioProperties(fa, fb)) return U("as propriedades de áudio mudariam");
            if (!samePictures(fa, fb)) return "as capas mudariam";

            TagLib::PropertyMap pa = fa->properties();
            TagLib::PropertyMap pb = fb->properties();

            // Chaves editadas (com nomes alternativos) ficam fora da comparação geral.
            std::vector<String> editedKeys;
            for (const auto &e: edits) {
                for (const auto &k: xiphCandidates(e.key)) editedKeys.push_back(k);
            }
            auto isEdited = [&](const String &k) {
                return std::find(editedKeys.begin(), editedKeys.end(), k) != editedKeys.end();
            };
            std::map<String, StringList> all;
            for (const auto &p: pa) all[p.first];
            for (const auto &p: pb) all[p.first];
            for (const auto &p: all) {
                if (isEdited(p.first)) continue;
                StringList va = pa.contains(p.first) ? pa[p.first] : StringList();
                StringList vb = pb.contains(p.first) ? pb[p.first] : StringList();
                if (va != vb) return "o campo " + p.first + U(" mudaria sem você ter pedido");
            }

            // Os campos editados têm de ter exatamente os novos valores.
            for (const auto &e: edits) {
                bool multi = currentValues(before, e.key).size() > 1;
                StringList expected = toValues(e.value, multi);
                StringList got = currentValues(pb, e.key);
                if (got != expected) {
                    return "o valor de " + e.key + U(" não ficou como digitado (") + join(got) + ")";
                }
            }

            if (kind == Kind::Mp3) {
                auto *ma = dynamic_cast<TagLib::MPEG::File *>(fa);
                auto *mb = dynamic_cast<TagLib::MPEG::File *>(fb);
                if (ma->hasID3v1Tag() != mb->hasID3v1Tag()) return "a tag ID3v1 mudaria";
                if (ma->hasAPETag() != mb->hasAPETag()) return "a tag APE mudaria";
                if (ma->hasID3v2Tag() && mb->ID3v2Tag()->header()->majorVersion() !=
                                         ma->ID3v2Tag()->header()->majorVersion()) {
                    return U("a versão do ID3v2 mudaria");
                }
                std::vector<ByteVector> ids;
                bool commentEdited = false;
                for (const auto &e: edits) {
                    if (e.key == "COMMENT") commentEdited = true;
                    ByteVector id = id3FrameFor(e.key);
                    if (!id.isEmpty()) ids.push_back(id);
                    // No ID3v2.3 a data vira TYER + TDAT (+ TIME); a data original vira TORY.
                    if (e.key == "DATE") {
                        ids.push_back("TYER");
                        ids.push_back("TDAT");
                        ids.push_back("TIME");
                    }
                    if (e.key == "ORIGINALDATE") ids.push_back("TORY");
                }
                if (!sameOtherId3Frames(ma, mb, ids, commentEdited, edits)) {
                    return "outros quadros ID3v2 mudariam";
                }
            }
            return "";
        }

        std::unique_ptr<TagLib::FileRef> open(const char *path) {
            auto ref = std::make_unique<TagLib::FileRef>(path, false);
            if (ref->isNull()) return nullptr;
            return ref;
        }
    }

    bool readFields(const char *path, String &format, std::vector<Field> &out) {
        auto ref = open(path);
        if (ref == nullptr) {
            format = U("não foi possível abrir o arquivo");
            return false;
        }
        Kind kind = kindOf(ref->file());
        switch (kind) {
            case Kind::Mp3: {
                auto *mp3 = dynamic_cast<TagLib::MPEG::File *>(ref->file());
                format = mp3->hasID3v2Tag()
                         ? U("MP3 · ID3v2.") + String::number(mp3->ID3v2Tag()->header()->majorVersion())
                         : String(U("MP3 · sem ID3v2 (será criado em v2.3)"));
                break;
            }
            case Kind::Flac:
                format = "FLAC";
                break;
            case Kind::Opus:
                format = "Opus";
                break;
            case Kind::Vorbis:
                format = "Ogg Vorbis";
                break;
            default:
                format = U("formato não suportado para edição");
                return false;
        }
        TagLib::PropertyMap props = ref->file()->properties();
        for (int i = 0; i < KEY_COUNT; i++) {
            out.push_back({KEYS[i], join(currentValues(props, KEYS[i]))});
        }
        return true;
    }

    bool readInfo(const char *path, std::vector<Field> &out) {
        TagLib::FileRef ref(path, true, TagLib::AudioProperties::Average);
        if (ref.isNull()) return false;
        TagLib::File *file = ref.file();
        TagLib::PropertyMap props = file->properties();
        auto first = [&](const char *key) {
            auto it = props.find(key);
            return it != props.end() ? join(it->second) : String();
        };
        out.push_back({"COMPOSER", first("COMPOSER")});
        out.push_back({"LYRICIST", first("LYRICIST")});
        out.push_back({"COMMENT", join(currentValues(props, "COMMENT"))});
        out.push_back({"ALBUMRATING", first("ALBUMRATING")});

        auto *audio = file->audioProperties();
        if (audio != nullptr) {
            out.push_back({"SAMPLERATE", String::number(audio->sampleRate())});
            out.push_back({"BITRATE", String::number(audio->bitrate())});
            int channels = audio->channels();
            String channelText = channels == 1 ? U("Mono")
                                 : channels == 2 ? U("Estéreo")
                                 : String::number(channels) + U(" canais");
            String bits;
            String encoding;
            if (auto *mp3 = dynamic_cast<TagLib::MPEG::File *>(file)) {
                auto *p = mp3->audioProperties();
                String version;
                switch (p->version()) {
                    case TagLib::MPEG::Header::Version1: version = "1"; break;
                    case TagLib::MPEG::Header::Version2: version = "2"; break;
                    case TagLib::MPEG::Header::Version2_5: version = "2.5"; break;
                    default: version = "4"; break;
                }
                encoding = "MPEG-" + version + " Layer " + String::number(p->layer());
                switch (p->channelMode()) {
                    case TagLib::MPEG::Header::JointStereo: channelText = "Joint Stereo"; break;
                    case TagLib::MPEG::Header::DualChannel: channelText = "Dual Channel"; break;
                    case TagLib::MPEG::Header::SingleChannel: channelText = "Mono"; break;
                    default: channelText = U("Estéreo"); break;
                }
                // MP3 não tem profundidade de bits; os decodificadores entregam 16 bits.
                bits = "16";
            } else if (auto *flac = dynamic_cast<TagLib::FLAC::File *>(file)) {
                bits = String::number(flac->audioProperties()->bitsPerSample());
                encoding = "FLAC";
            } else if (dynamic_cast<TagLib::Ogg::Opus::File *>(file)) {
                encoding = "Opus";
            } else if (dynamic_cast<TagLib::Ogg::Vorbis::File *>(file)) {
                encoding = "Vorbis";
            }
            out.push_back({"CHANNELS", channelText});
            out.push_back({"BITS", bits});
            out.push_back({"ENCODING", encoding});
        }
        return true;
    }

    String writeAndVerify(const char *original, const char *work, const std::vector<Field> &edits) {
        if (edits.empty()) return "nada para gravar";
        for (const auto &e: edits) {
            bool known = false;
            for (int i = 0; i < KEY_COUNT; i++) known = known || e.key == KEYS[i];
            if (!known) return "campo desconhecido: " + e.key;
        }

        Kind kind;
        TagLib::PropertyMap before;
        {
            auto ref = open(work);
            if (ref == nullptr) return U("não foi possível abrir a cópia do arquivo");
            kind = kindOf(ref->file());
            if (kind == Kind::Unsupported) return U("formato não suportado para edição");
            before = ref->file()->properties();
            String error;
            switch (kind) {
                case Kind::Mp3:
                    error = writeMp3(dynamic_cast<TagLib::MPEG::File *>(ref->file()), edits, before);
                    break;
                case Kind::Flac: {
                    auto *flac = dynamic_cast<TagLib::FLAC::File *>(ref->file());
                    error = writeXiph(flac, flac->xiphComment(true), edits, before);
                    break;
                }
                case Kind::Opus: {
                    auto *opus = dynamic_cast<TagLib::Ogg::Opus::File *>(ref->file());
                    error = writeXiph(opus, opus->tag(), edits, before);
                    break;
                }
                case Kind::Vorbis: {
                    auto *vorbis = dynamic_cast<TagLib::Ogg::Vorbis::File *>(ref->file());
                    error = writeXiph(vorbis, vorbis->tag(), edits, before);
                    break;
                }
                default:
                    error = U("formato não suportado para edição");
            }
            if (!error.isEmpty()) return error;
        } // fecha o arquivo antes de conferir

        String audioError = verifyAudio(kind, original, work);
        if (!audioError.isEmpty()) return audioError;
        return verifyTags(kind, original, work, edits, before);
    }
}
