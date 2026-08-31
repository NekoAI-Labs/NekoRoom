// MoeChat JNI bridge: minimal MNN LLM dialog
// Loads LLM via config_path → response(prompt) streams tokens through a Kotlin callback.
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <functional>
#include <memory>
#include <ostream>
#include <sstream>
#include <streambuf>
#include <string>
#include <vector>

#include "llm/llm.hpp"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "MoeAvatarLLM", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MoeAvatarLLM", __VA_ARGS__)

using MNN::Transformer::Llm;

namespace {

// streambuf that flushes each chunk into a callback (UTF-8 token bytes)
class CallbackStreamBuf : public std::streambuf {
public:
    using Cb = std::function<bool(const std::string&)>;  // return true to stop
    explicit CallbackStreamBuf(Cb cb) : cb_(std::move(cb)) {}
    bool stopped() const { return stopped_; }
protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (n > 0 && cb_) {
            stopped_ = stopped_ || cb_(std::string(s, static_cast<size_t>(n)));
        }
        return n;
    }
private:
    Cb cb_;
    bool stopped_ = false;
};

struct Session {
    std::unique_ptr<Llm> llm;
    std::atomic<bool> stop_flag{false};
};

inline Session* asSession(jlong ptr) {
    return reinterpret_cast<Session*>(ptr);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_moeavatar_llm_LocalLlmBridge_initNative(JNIEnv* env, jobject /*thiz*/, jstring jConfigPath,
                                                  jstring jSamplingOverrides) {
    const char* cfg = env->GetStringUTFChars(jConfigPath, nullptr);
    LOGI("initNative configPath=%s", cfg);
    auto* session = new Session();
    session->llm.reset(Llm::createLLM(cfg));
    env->ReleaseStringUTFChars(jConfigPath, cfg);
    if (!session->llm) {
        LOGE("createLLM returned null");
        delete session;
        return 0;
    }
    const char* overrides = jSamplingOverrides ? env->GetStringUTFChars(jSamplingOverrides, nullptr) : nullptr;
    if (overrides && overrides[0] != '\0') {
        try {
            if (!session->llm->set_config(overrides)) {
                LOGE("Llm::set_config(sampling) failed");
                env->ReleaseStringUTFChars(jSamplingOverrides, overrides);
                delete session;
                return 0;
            }
            LOGI("sampling overrides=%s", overrides);
        } catch (const std::exception& e) {
            LOGE("Llm::set_config(sampling) exception: %s", e.what());
            env->ReleaseStringUTFChars(jSamplingOverrides, overrides);
            delete session;
            return 0;
        }
        env->ReleaseStringUTFChars(jSamplingOverrides, overrides);
    }
    if (!session->llm->load()) {
        LOGE("Llm::load() failed");
        delete session;
        return 0;
    }
    LOGI("Llm load OK ptr=%p", session);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_moeavatar_llm_LocalLlmBridge_resetNative(JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    auto* session = asSession(ptr);
    if (session && session->llm) {
        session->llm->reset();
    }
}

JNIEXPORT void JNICALL
Java_com_moeavatar_llm_LocalLlmBridge_stopNative(JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    auto* session = asSession(ptr);
    if (session) {
        session->stop_flag.store(true);
    }
}

JNIEXPORT void JNICALL
Java_com_moeavatar_llm_LocalLlmBridge_releaseNative(JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    auto* session = asSession(ptr);
    delete session;
}

JNIEXPORT jstring JNICALL
Java_com_moeavatar_llm_LocalLlmBridge_submitNative(JNIEnv* env, jobject /*thiz*/,
                                        jlong ptr, jstring jPrompt, jobject jListener) {
    auto* session = asSession(ptr);
    if (!session || !session->llm) {
        return env->NewStringUTF("");
    }
    session->stop_flag.store(false);

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string promptStr(prompt);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    // Resolve listener.onToken(String) once
    jclass listenerCls = env->GetObjectClass(jListener);
    jmethodID onTokenMid = env->GetMethodID(listenerCls, "onToken", "(Ljava/lang/String;)Z");
    if (onTokenMid == nullptr) {
        LOGE("listener.onToken(String):Z not found");
        env->DeleteLocalRef(listenerCls);
        return env->NewStringUTF("");
    }

    // Find length in bytes of the longest UTF-8-complete prefix of [data, data+n).
    // Tokens may split a multi-byte char; flushing a half char to NewStringUTF aborts the JVM.
    auto utf8CompletePrefix = [](const char* data, size_t n) -> size_t {
        if (n == 0) return 0;
        size_t i = n;
        // walk back over continuation bytes (10xxxxxx)
        while (i > 0 && (static_cast<unsigned char>(data[i - 1]) & 0xC0) == 0x80) {
            --i;
        }
        if (i == 0) return 0;
        unsigned char lead = static_cast<unsigned char>(data[i - 1]);
        size_t need = 1;
        if      ((lead & 0x80) == 0x00) need = 1;
        else if ((lead & 0xE0) == 0xC0) need = 2;
        else if ((lead & 0xF0) == 0xE0) need = 3;
        else if ((lead & 0xF8) == 0xF0) need = 4;
        else return i - 1;  // malformed lead, drop it
        size_t have = n - (i - 1);
        return (have >= need) ? n : (i - 1);
    };

    std::string pending;
    std::stringstream allText;
    auto flush = [&](const std::string& s) -> bool {
        if (s.empty()) return false;
        jstring js = env->NewStringUTF(s.c_str());
        jboolean stop = env->CallBooleanMethod(jListener, onTokenMid, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return true;
        }
        return stop == JNI_TRUE;
    };

    auto cb = [&](const std::string& chunk) -> bool {
        if (session->stop_flag.load()) return true;
        allText << chunk;
        pending.append(chunk);
        size_t cut = utf8CompletePrefix(pending.data(), pending.size());
        if (cut == 0) return false;  // wait for more bytes
        std::string emit(pending.data(), cut);
        pending.erase(0, cut);
        return flush(emit);
    };

    CallbackStreamBuf buf(cb);
    std::ostream os(&buf);
    try {
        session->llm->response(promptStr, &os, "<eop>", -1);
    } catch (const std::exception& e) {
        LOGE("response exception: %s", e.what());
    }
    // Flush any tail bytes (drop if still malformed at end).
    if (!pending.empty()) {
        size_t cut = utf8CompletePrefix(pending.data(), pending.size());
        if (cut > 0) flush(std::string(pending.data(), cut));
    }

    env->DeleteLocalRef(listenerCls);
    return env->NewStringUTF(allText.str().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_moeavatar_llm_LocalLlmBridge_submitChatNative(JNIEnv* env, jobject /*thiz*/,
                                                       jlong ptr, jobjectArray jRoles,
                                                       jobjectArray jContents, jint maxNewTokens,
                                                       jobject jListener) {
    auto* session = asSession(ptr);
    if (!session || !session->llm || !jRoles || !jContents) {
        return env->NewStringUTF("");
    }
    const jsize count = env->GetArrayLength(jRoles);
    if (count != env->GetArrayLength(jContents)) {
        LOGE("roles/contents length mismatch");
        return env->NewStringUTF("");
    }

    MNN::Transformer::ChatMessages messages;
    for (jsize i = 0; i < count; ++i) {
        auto* jRole = static_cast<jstring>(env->GetObjectArrayElement(jRoles, i));
        auto* jContent = static_cast<jstring>(env->GetObjectArrayElement(jContents, i));
        const char* role = env->GetStringUTFChars(jRole, nullptr);
        const char* content = env->GetStringUTFChars(jContent, nullptr);
        messages.emplace_back(role ? role : "", content ? content : "");
        env->ReleaseStringUTFChars(jRole, role);
        env->ReleaseStringUTFChars(jContent, content);
        env->DeleteLocalRef(jRole);
        env->DeleteLocalRef(jContent);
    }
    session->stop_flag.store(false);
    LOGI("response(ChatMessages): messages=%d maxNewTokens=%d", static_cast<int>(count),
         static_cast<int>(maxNewTokens));

    jclass listenerCls = env->GetObjectClass(jListener);
    jmethodID onTokenMid = env->GetMethodID(listenerCls, "onToken", "(Ljava/lang/String;)Z");
    if (onTokenMid == nullptr) {
        LOGE("listener.onToken(String):Z not found");
        env->DeleteLocalRef(listenerCls);
        return env->NewStringUTF("");
    }

    auto utf8CompletePrefix = [](const char* data, size_t n) -> size_t {
        if (n == 0) return 0;
        size_t i = n;
        while (i > 0 && (static_cast<unsigned char>(data[i - 1]) & 0xC0) == 0x80) --i;
        if (i == 0) return 0;
        const unsigned char lead = static_cast<unsigned char>(data[i - 1]);
        size_t need = 1;
        if ((lead & 0x80) == 0x00) need = 1;
        else if ((lead & 0xE0) == 0xC0) need = 2;
        else if ((lead & 0xF0) == 0xE0) need = 3;
        else if ((lead & 0xF8) == 0xF0) need = 4;
        else return i - 1;
        return n - (i - 1) >= need ? n : i - 1;
    };

    std::string pending;
    std::stringstream allText;
    auto flush = [&](const std::string& text) -> bool {
        if (text.empty()) return false;
        jstring js = env->NewStringUTF(text.c_str());
        jboolean stop = env->CallBooleanMethod(jListener, onTokenMid, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return true;
        }
        return stop == JNI_TRUE;
    };
    auto cb = [&](const std::string& chunk) -> bool {
        if (session->stop_flag.load()) return true;
        allText << chunk;
        pending.append(chunk);
        const size_t cut = utf8CompletePrefix(pending.data(), pending.size());
        if (cut == 0) return false;
        std::string emit(pending.data(), cut);
        pending.erase(0, cut);
        return flush(emit);
    };

    CallbackStreamBuf buf(cb);
    std::ostream os(&buf);
    try {
        session->llm->response(messages, &os, "<eop>", maxNewTokens);
    } catch (const std::exception& e) {
        LOGE("response(ChatMessages) exception: %s", e.what());
    }
    if (!pending.empty()) {
        const size_t cut = utf8CompletePrefix(pending.data(), pending.size());
        if (cut > 0) flush(std::string(pending.data(), cut));
    }
    env->DeleteLocalRef(listenerCls);
    return env->NewStringUTF(allText.str().c_str());
}

}  // extern "C"
