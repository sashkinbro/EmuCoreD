#include "nintendo_renderer.h"

#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#if defined(EMUCORED_DIAGNOSTICS) && EMUCORED_DIAGNOSTICS
#define NUI_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "NintendoUI", __VA_ARGS__)
#define NUI_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NintendoUI", __VA_ARGS__)
#else
#define NUI_LOGD(...) ((void)0)
#define NUI_LOGI(...) ((void)0)
#endif
#define NUI_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NintendoUI", __VA_ARGS__)

namespace {

JavaVM* g_vm = nullptr;
std::mutex g_jniMutex;
jclass g_rasterizerClass = nullptr;
jmethodID g_rasterizeMethod = nullptr;
jclass g_audioClass = nullptr;
jmethodID g_playSfxMethod = nullptr;
jclass g_decoderClass = nullptr;
jmethodID g_decodeMethod = nullptr;
jclass g_hostClass = nullptr;
jmethodID g_hostActionMethod = nullptr;

std::mutex g_rendererMutex;
std::unique_ptr<nintendoui::NintendoRenderer> g_renderer;

JNIEnv* attachCurrentThread(bool& didAttach) {
    didAttach = false;
    if (!g_vm) return nullptr;
    JNIEnv* env = nullptr;
    jint status = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) return env;
    if (status == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            didAttach = true;
            return env;
        }
    }
    return nullptr;
}

void detachIfNeeded(bool didAttach) {
    if (didAttach && g_vm) {
        g_vm->DetachCurrentThread();
    }
}

std::string jstringToStd(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

} // namespace

namespace nintendoui::bridge {

bool rasterizeTextBitmap(const std::string& text, float fontPx, int maxWidthPx, bool bold, int align,
                         int& outW, int& outH, std::vector<uint8_t>& outRgba) {
    bool didAttach = false;
    JNIEnv* env = attachCurrentThread(didAttach);
    if (!env) return false;

    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard lock(g_jniMutex);
        cls = g_rasterizerClass;
        mid = g_rasterizeMethod;
    }
    if (!cls || !mid) {
        detachIfNeeded(didAttach);
        return false;
    }

    jstring jText = env->NewStringUTF(text.c_str());
    jintArray meta = env->NewIntArray(2);
    auto jBytes = static_cast<jbyteArray>(
        env->CallStaticObjectMethod(cls, mid, jText, fontPx, maxWidthPx, bold ? JNI_TRUE : JNI_FALSE, align, meta)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        NUI_LOGE("Exception in NintendoTextRasterizer.rasterize");
        env->DeleteLocalRef(jText);
        env->DeleteLocalRef(meta);
        if (jBytes) env->DeleteLocalRef(jBytes);
        detachIfNeeded(didAttach);
        return false;
    }

    bool ok = false;
    if (jBytes) {
        jint wh[2]{0, 0};
        env->GetIntArrayRegion(meta, 0, 2, wh);
        outW = wh[0];
        outH = wh[1];
        const jsize len = env->GetArrayLength(jBytes);
        if (outW > 0 && outH > 0 && len == outW * outH * 4) {
            outRgba.resize(static_cast<size_t>(len));
            env->GetByteArrayRegion(jBytes, 0, len, reinterpret_cast<jbyte*>(outRgba.data()));
            ok = true;
        }
        env->DeleteLocalRef(jBytes);
    }

    env->DeleteLocalRef(jText);
    env->DeleteLocalRef(meta);
    detachIfNeeded(didAttach);
    return ok;
}

void triggerAudioCue(int sfxId) {
    bool didAttach = false;
    JNIEnv* env = attachCurrentThread(didAttach);
    if (!env) return;

    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard lock(g_jniMutex);
        cls = g_audioClass;
        mid = g_playSfxMethod;
    }
    if (cls && mid) {
        env->CallStaticVoidMethod(cls, mid, sfxId);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    detachIfNeeded(didAttach);
}

void requestAsyncImageDecode(const std::string& key, const std::string& path) {
    bool didAttach = false;
    JNIEnv* env = attachCurrentThread(didAttach);
    if (!env) return;

    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard lock(g_jniMutex);
        cls = g_decoderClass;
        mid = g_decodeMethod;
    }
    if (cls && mid) {
        jstring jKey = env->NewStringUTF(key.c_str());
        jstring jPath = env->NewStringUTF(path.c_str());
        env->CallStaticVoidMethod(cls, mid, jKey, jPath);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            NUI_LOGE("Exception in NintendoImageDecoder.enqueueDecode");
        }
        env->DeleteLocalRef(jKey);
        env->DeleteLocalRef(jPath);
    }
    detachIfNeeded(didAttach);
}

} // namespace nintendoui::bridge

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeInitBridge(JNIEnv* env, jclass) {
    std::lock_guard lock(g_jniMutex);
    auto cacheClass = [&](const char* name, jclass& outCls) {
        if (outCls) return true;
        jclass local = env->FindClass(name);
        if (!local || env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            NUI_LOGE("Failed to FindClass %s", name);
            return false;
        }
        outCls = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        return true;
    };

    if (cacheClass("com/sbro/emucored/nintendoui/NintendoTextRasterizer", g_rasterizerClass) && !g_rasterizeMethod) {
        g_rasterizeMethod = env->GetStaticMethodID(
            g_rasterizerClass,
            "rasterize",
            "(Ljava/lang/String;FIZI[I)[B"
        );
        if (env->ExceptionCheck()) { env->ExceptionClear(); NUI_LOGE("Missing rasterize method"); }
    }
    if (cacheClass("com/sbro/emucored/nintendoui/NintendoShellAudio", g_audioClass) && !g_playSfxMethod) {
        g_playSfxMethod = env->GetStaticMethodID(g_audioClass, "playSfx", "(I)V");
        if (env->ExceptionCheck()) { env->ExceptionClear(); NUI_LOGE("Missing playSfx method"); }
    }
    if (cacheClass("com/sbro/emucored/nintendoui/NintendoImageDecoder", g_decoderClass) && !g_decodeMethod) {
        g_decodeMethod = env->GetStaticMethodID(
            g_decoderClass,
            "enqueueDecode",
            "(Ljava/lang/String;Ljava/lang/String;)V"
        );
        if (env->ExceptionCheck()) { env->ExceptionClear(); NUI_LOGE("Missing enqueueDecode method"); }
    }
    if (cacheClass("com/sbro/emucored/nintendoui/NintendoShellHost", g_hostClass) && !g_hostActionMethod) {
        g_hostActionMethod = env->GetStaticMethodID(
            g_hostClass,
            "onNativeAction",
            "(ILjava/lang/String;Ljava/lang/String;II)V"
        );
        if (env->ExceptionCheck()) { env->ExceptionClear(); NUI_LOGE("Missing onNativeAction method"); }
    }

    std::lock_guard rLock(g_rendererMutex);
    if (!g_renderer) {
        g_renderer = std::make_unique<nintendoui::NintendoRenderer>();
    }
    NUI_LOGI("NintendoShell bridge initialized");
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeOnSurfaceCreated(JNIEnv*, jclass) {
    std::lock_guard lock(g_rendererMutex);
    if (!g_renderer) g_renderer = std::make_unique<nintendoui::NintendoRenderer>();
    g_renderer->onSurfaceCreated();
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeOnSurfaceChanged(JNIEnv*, jclass, jint w, jint h) {
    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->onSurfaceChanged(w, h);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeDrawFrame(JNIEnv* env, jclass, jfloat dt) {
    nintendoui::NintendoRenderer* renderer = nullptr;
    {
        std::lock_guard lock(g_rendererMutex);
        renderer = g_renderer.get();
    }
    if (!renderer) return;

    renderer->onDrawFrame(dt);

    // Drain any pending SFX, cover decode requests, and host navigation actions cleanly
    auto requests = renderer->drainImageRequests(2);
    for (const auto& [key, path] : requests) {
        nintendoui::bridge::requestAsyncImageDecode(key, path);
    }
    auto actions = renderer->drainActions();
    jclass hostCls = nullptr;
    jmethodID hostMid = nullptr;
    {
        std::lock_guard lock(g_jniMutex);
        hostCls = g_hostClass;
        hostMid = g_hostActionMethod;
    }
    for (const auto& act : actions) {
        if (act.type == nintendoui::HostActionType::PlaySfx) {
            nintendoui::bridge::triggerAudioCue(act.intArg1);
        } else if (hostCls && hostMid) {
            jstring jArg1 = env->NewStringUTF(act.arg1.c_str());
            jstring jArg2 = env->NewStringUTF(act.arg2.c_str());
            env->CallStaticVoidMethod(
                hostCls, hostMid,
                static_cast<jint>(act.type), jArg1, jArg2,
                static_cast<jint>(act.intArg1), static_cast<jint>(act.intArg2)
            );
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                NUI_LOGE("Exception dispatching NintendoShellHost.onNativeAction");
            }
            env->DeleteLocalRef(jArg1);
            env->DeleteLocalRef(jArg2);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeReleaseGl(JNIEnv*, jclass) {
    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->releaseGlResources();
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeSetStrings(
    JNIEnv* env, jclass, jobjectArray keys, jobjectArray values) {
    if (!keys || !values) return;
    const jsize n = std::min(env->GetArrayLength(keys), env->GetArrayLength(values));
    std::unordered_map<std::string, std::string> map;
    map.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto jk = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
        auto jv = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        map.emplace(jstringToStd(env, jk), jstringToStd(env, jv));
        if (jk) env->DeleteLocalRef(jk);
        if (jv) env->DeleteLocalRef(jv);
    }
    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->setLocalizedStrings(map);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeSetCatalog(
    JNIEnv* env, jclass,
    jobjectArray paths, jobjectArray titles, jobjectArray consoleIds,
    jobjectArray consoleShorts, jobjectArray consoleLabels,
    jobjectArray coverKeys, jobjectArray coverPaths,
    jobjectArray recentPaths) {
    const jsize n = paths ? env->GetArrayLength(paths) : 0;
    std::vector<nintendoui::GameEntry> games;
    games.reserve(static_cast<size_t>(n));

    auto getElem = [&](jobjectArray arr, jsize i) -> std::string {
        if (!arr || i >= env->GetArrayLength(arr)) return {};
        auto js = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        std::string s = jstringToStd(env, js);
        if (js) env->DeleteLocalRef(js);
        return s;
    };

    for (jsize i = 0; i < n; i++) {
        nintendoui::GameEntry g;
        g.path = getElem(paths, i);
        g.title = getElem(titles, i);
        g.consoleId = getElem(consoleIds, i);
        g.consoleShort = getElem(consoleShorts, i);
        g.consoleLabel = getElem(consoleLabels, i);
        g.coverKey = getElem(coverKeys, i);
        g.coverPath = getElem(coverPaths, i);
        games.push_back(std::move(g));
    }

    std::vector<std::string> recents;
    const jsize rn = recentPaths ? env->GetArrayLength(recentPaths) : 0;
    recents.reserve(static_cast<size_t>(rn));
    for (jsize i = 0; i < rn; i++) {
        recents.push_back(getElem(recentPaths, i));
    }

    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->setCatalog(std::move(games), std::move(recents));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeSetQuickSaves(
    JNIEnv* env, jclass,
    jobjectArray ids, jobjectArray gamePaths, jobjectArray gameTitles,
    jobjectArray consoleIds, jobjectArray consoleShorts,
    jobjectArray slotLabels, jobjectArray timeTexts,
    jobjectArray sizeTexts, jobjectArray previewPaths, jintArray slots) {
    const jsize n = ids ? env->GetArrayLength(ids) : 0;
    std::vector<nintendoui::QuickSaveEntry> out;
    out.reserve(static_cast<size_t>(n));

    auto getElem = [&](jobjectArray arr, jsize i) -> std::string {
        if (!arr || i >= env->GetArrayLength(arr)) return {};
        auto js = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        std::string s = jstringToStd(env, js);
        if (js) env->DeleteLocalRef(js);
        return s;
    };

    std::vector<jint> slotVals(static_cast<size_t>(n), 0);
    if (slots && n > 0) {
        env->GetIntArrayRegion(slots, 0, n, slotVals.data());
    }

    for (jsize i = 0; i < n; i++) {
        nintendoui::QuickSaveEntry e;
        e.id = getElem(ids, i);
        e.gamePath = getElem(gamePaths, i);
        e.gameTitle = getElem(gameTitles, i);
        e.consoleId = getElem(consoleIds, i);
        e.consoleShort = getElem(consoleShorts, i);
        e.slotLabel = getElem(slotLabels, i);
        e.timestampText = getElem(timeTexts, i);
        e.sizeText = getElem(sizeTexts, i);
        e.previewPath = getElem(previewPaths, i);
        e.slot = slotVals[static_cast<size_t>(i)];
        out.push_back(std::move(e));
    }

    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->setQuickSaves(std::move(out));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeSetMemorySaves(
    JNIEnv* env, jclass,
    jobjectArray ids, jobjectArray filePaths, jobjectArray titles,
    jobjectArray consoleIds, jobjectArray consoleShorts,
    jobjectArray sizeTexts, jobjectArray timeTexts) {
    const jsize n = ids ? env->GetArrayLength(ids) : 0;
    std::vector<nintendoui::MemorySaveEntry> out;
    out.reserve(static_cast<size_t>(n));

    auto getElem = [&](jobjectArray arr, jsize i) -> std::string {
        if (!arr || i >= env->GetArrayLength(arr)) return {};
        auto js = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        std::string s = jstringToStd(env, js);
        if (js) env->DeleteLocalRef(js);
        return s;
    };

    for (jsize i = 0; i < n; i++) {
        nintendoui::MemorySaveEntry e;
        e.id = getElem(ids, i);
        e.filePath = getElem(filePaths, i);
        e.title = getElem(titles, i);
        e.consoleId = getElem(consoleIds, i);
        e.consoleShort = getElem(consoleShorts, i);
        e.sizeText = getElem(sizeTexts, i);
        e.timestampText = getElem(timeTexts, i);
        out.push_back(std::move(e));
    }

    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->setMemorySaves(std::move(out));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeSetSettings(
    JNIEnv*, jclass, jint themeId, jint aspectMode, jboolean ecoMode, jboolean sfxEnabled) {
    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->setSettings(themeId, aspectMode, ecoMode == JNI_TRUE, sfxEnabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeSetClock(JNIEnv* env, jclass, jstring clock) {
    std::string text = jstringToStd(env, clock);
    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->setClockText(std::move(text));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeShowToast(JNIEnv* env, jclass, jstring msg) {
    std::string text = jstringToStd(env, msg);
    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->showToast(std::move(text));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeOnTouch(
    JNIEnv*, jclass, jint action, jfloat x, jfloat y) {
    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->onTouchEvent(action, x, y);
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeOnKey(
    JNIEnv*, jclass, jint keyCode, jboolean down) {
    std::lock_guard lock(g_rendererMutex);
    if (!g_renderer) return JNI_FALSE;
    return g_renderer->onKeyEvent(keyCode, down == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeOnAxis(
    JNIEnv*, jclass, jfloat ax, jfloat ay) {
    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->onAnalogAxis(ax, ay);
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeOnBackPressed(JNIEnv*, jclass) {
    std::lock_guard lock(g_rendererMutex);
    if (!g_renderer) return JNI_FALSE;
    bool handled = false;
    g_renderer->onBackPressed(handled);
    return handled ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeUploadImage(
    JNIEnv* env, jclass, jstring jKey, jint w, jint h, jbyteArray jRgba) {
    std::string key = jstringToStd(env, jKey);
    if (!jRgba || w <= 0 || h <= 0) {
        std::lock_guard lock(g_rendererMutex);
        if (g_renderer) g_renderer->markImageFailed(key);
        return;
    }
    const jsize len = env->GetArrayLength(jRgba);
    if (len != w * h * 4) {
        std::lock_guard lock(g_rendererMutex);
        if (g_renderer) g_renderer->markImageFailed(key);
        return;
    }
    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(jRgba, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->uploadImagePixels(key, w, h, buf);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucored_nintendoui_NintendoShellNative_nativeMarkImageFailed(
    JNIEnv* env, jclass, jstring jKey) {
    std::string key = jstringToStd(env, jKey);
    std::lock_guard lock(g_rendererMutex);
    if (g_renderer) g_renderer->markImageFailed(key);
}

} // extern "C"
