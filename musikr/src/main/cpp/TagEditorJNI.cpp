/*
 * Copyright (c) 2026 Auxio Project
 * TagEditorJNI.cpp is part of Auxio.
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

// Fork pessoal: ponte JNI do editor de tags (ver TagEditor.h).

#include <jni.h>
#include <exception>
#include <string>
#include <vector>

#include "TagEditor.h"
#include "taglib/tbytevector.h"

namespace {
    // Converte exatamente (UTF-16), sem passar pelo "UTF-8 modificado" do JNI.
    TagLib::String fromJava(JNIEnv *env, jstring s) {
        if (s == nullptr) return TagLib::String();
        jsize len = env->GetStringLength(s);
        const jchar *chars = env->GetStringChars(s, nullptr);
        TagLib::ByteVector bytes(reinterpret_cast<const char *>(chars),
                static_cast<unsigned int>(len * 2));
        env->ReleaseStringChars(s, chars);
        return TagLib::String(bytes, TagLib::String::UTF16LE);
    }

    jstring toJava(JNIEnv *env, const TagLib::String &s) {
        TagLib::ByteVector bytes = s.data(TagLib::String::UTF16LE);
        return env->NewString(reinterpret_cast<const jchar *>(bytes.data()),
                static_cast<jsize>(bytes.size() / 2));
    }

    std::string pathOf(JNIEnv *env, jstring s) {
        const char *chars = env->GetStringUTFChars(s, nullptr);
        std::string out(chars);
        env->ReleaseStringUTFChars(s, chars);
        return out;
    }
}

/*
 * Retorna [formato, chave1, valor1, chave2, valor2, ...]. Se o formato não for editável, retorna só
 * ["!" + motivo].
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_oxycblt_musikr_tag_edit_TagEditorJNI_readNative(JNIEnv *env, jobject /* this */,
        jstring path) {
    TagLib::String format;
    std::vector<tagedit::Field> fields;
    bool ok;
    try {
        ok = tagedit::readFields(pathOf(env, path).c_str(), format, fields);
    } catch (std::exception &e) {
        ok = false;
        format = TagLib::String(e.what(), TagLib::String::UTF8);
    }
    jclass stringClass = env->FindClass("java/lang/String");
    if (!ok) {
        jobjectArray out = env->NewObjectArray(1, stringClass, nullptr);
        env->SetObjectArrayElement(out, 0, toJava(env, "!" + format));
        return out;
    }
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(1 + fields.size() * 2), stringClass,
            nullptr);
    env->SetObjectArrayElement(out, 0, toJava(env, format));
    jsize i = 1;
    for (const auto &f: fields) {
        jstring k = toJava(env, f.key);
        jstring v = toJava(env, f.value);
        env->SetObjectArrayElement(out, i++, k);
        env->SetObjectArrayElement(out, i++, v);
        env->DeleteLocalRef(k);
        env->DeleteLocalRef(v);
    }
    return out;
}

/* Grava as edições na cópia [work] e confere contra [original]. Retorna "" se deu tudo certo. */
extern "C" JNIEXPORT jstring JNICALL
Java_org_oxycblt_musikr_tag_edit_TagEditorJNI_writeNative(JNIEnv *env, jobject /* this */,
        jstring original, jstring work, jobjectArray keys, jobjectArray values) {
    std::vector<tagedit::Field> edits;
    jsize count = env->GetArrayLength(keys);
    for (jsize i = 0; i < count; i++) {
        auto k = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
        auto v = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        edits.push_back({fromJava(env, k), fromJava(env, v)});
        env->DeleteLocalRef(k);
        env->DeleteLocalRef(v);
    }
    TagLib::String result;
    try {
        result = tagedit::writeAndVerify(pathOf(env, original).c_str(), pathOf(env, work).c_str(),
                edits);
    } catch (std::exception &e) {
        result = TagLib::String(e.what(), TagLib::String::UTF8);
    }
    return toJava(env, result);
}

/* Fork pessoal: informações extras da música, como [chave1, valor1, chave2, valor2, ...]. */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_oxycblt_musikr_tag_edit_TagEditorJNI_infoNative(JNIEnv *env, jobject /* this */,
        jstring path) {
    std::vector<tagedit::Field> fields;
    try {
        tagedit::readInfo(pathOf(env, path).c_str(), fields);
    } catch (std::exception &e) {
        fields.clear();
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(fields.size() * 2), stringClass,
            nullptr);
    jsize i = 0;
    for (const auto &f: fields) {
        jstring k = toJava(env, f.key);
        jstring v = toJava(env, f.value);
        env->SetObjectArrayElement(out, i++, k);
        env->SetObjectArrayElement(out, i++, v);
        env->DeleteLocalRef(k);
        env->DeleteLocalRef(v);
    }
    return out;
}
