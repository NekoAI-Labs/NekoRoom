/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include <jni.h>
#include "JniBridgeC.hpp"
#include "LAppDelegate.hpp"
#include "LAppPal.hpp"
#include <android/log.h>

using namespace Csm;

static JavaVM *g_JVM; // JavaVM is valid for all threads, so just save it globally
static jclass g_JniBridgeJavaClass;
static jmethodID g_LoadFileMethodId;
static jmethodID g_MoveTaskToBackMethodId;
static jmethodID g_OnLoadErrorMethodId;
static jmethodID g_OnLoadDoneMethodId;
static jmethodID g_OnLoadOneMotionMethodId;
static jmethodID g_OnLoadOneExpressionMethodId;

JNIEnv *GetEnv() {
    JNIEnv *env = NULL;
    g_JVM->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

// The VM calls JNI_OnLoad when the native library is loaded
jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_JVM = vm;

    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/chatwaifu/live2d/JniBridgeJava");
    g_JniBridgeJavaClass = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    g_LoadFileMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "LoadFile",
                                                "(Ljava/lang/String;)[B");
    g_MoveTaskToBackMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "MoveTaskToBack",
                                                      "()V");
    g_OnLoadErrorMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "OnLoadError", "()V");
    g_OnLoadDoneMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "OnLoadDone", "()V");
    g_OnLoadOneMotionMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "OnLoadOneMotion",
                                                       "(Ljava/lang/String;ILjava/lang/String;)V");
    g_OnLoadOneExpressionMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass,
                                                           "OnLoadOneExpression",
                                                           "(Ljava/lang/String;I)V");

    return JNI_VERSION_1_6;
}

void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env = GetEnv();
    env->DeleteGlobalRef(g_JniBridgeJavaClass);
}

char *JniBridgeC::LoadFileAsBytesFromJava(const char *filePath, unsigned int *outSize) {
    JNIEnv *env = GetEnv();
    *outSize = 0;

    // ファイルロード
    jbyteArray obj = (jbyteArray) env->CallStaticObjectMethod(g_JniBridgeJavaClass,
                                                              g_LoadFileMethodId,
                                                              env->NewStringUTF(filePath));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, "MoeAvatar.Live2D", "LoadFile threw: %s", filePath);
        return nullptr;
    }
    if (obj == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "MoeAvatar.Live2D", "LoadFile missing: %s", filePath);
        return nullptr;
    }
    *outSize = static_cast<unsigned int>(env->GetArrayLength(obj));
    if (*outSize == 0) return nullptr;

    char *buffer = new char[*outSize];
    env->GetByteArrayRegion(obj, 0, *outSize, reinterpret_cast<jbyte *>(buffer));

    return buffer;
}

void JniBridgeC::MoveTaskToBack() {
    JNIEnv *env = GetEnv();

    // アプリ終了
    env->CallStaticVoidMethod(g_JniBridgeJavaClass, g_MoveTaskToBackMethodId, NULL);
}

void JniBridgeC::OnLoadError() {
    JNIEnv *env = GetEnv();

    // モデルロードエラー
    env->CallStaticVoidMethod(g_JniBridgeJavaClass, g_OnLoadErrorMethodId);
}

void JniBridgeC::OnLoadDone() {
    JNIEnv *env = GetEnv();

    // モデルロード完成
    env->CallStaticVoidMethod(g_JniBridgeJavaClass, g_OnLoadDoneMethodId);
}

void JniBridgeC::OnLoadOneMotion(const char *motionGroup, int index, const char *motionName) {
    JNIEnv *env = GetEnv();

    env->CallStaticVoidMethod(g_JniBridgeJavaClass, g_OnLoadOneMotionMethodId,
                              env->NewStringUTF(motionGroup), index, env->NewStringUTF(motionName));

}

void JniBridgeC::OnLoadOneExpression(const char *expressionName, int index) {
    JNIEnv *env = GetEnv();
    env->CallStaticVoidMethod(g_JniBridgeJavaClass, g_OnLoadOneExpressionMethodId,
                              env->NewStringUTF(expressionName), index);

}

extern "C"
{
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeOnStart(JNIEnv *env, jclass type) {
    LAppDelegate::GetInstance()->OnStart();
}

JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeOnPause(JNIEnv *env, jclass type) {
    LAppDelegate::GetInstance()->OnPause();
}

JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeOnStop(JNIEnv *env, jclass type) {
    LAppDelegate::GetInstance()->OnStop();
}

JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeOnDestroy(JNIEnv *env, jclass type) {
    LAppDelegate::GetInstance()->OnDestroy();
}

JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeOnSurfaceCreated(JNIEnv *env, jclass type) {
    LAppDelegate::GetInstance()->OnSurfaceCreate();
}

JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeOnSurfaceChanged(JNIEnv *env, jclass type, jint width,
                                                               jint height) {
    LAppDelegate::GetInstance()->OnSurfaceChanged(width, height);
}

JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeOnDrawFrame(JNIEnv *env, jclass type) {
    LAppDelegate::GetInstance()->Run();
}

JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeOnTouchesBegan(JNIEnv *env, jclass type,
                                                             jfloat pointX, jfloat pointY) {
    LAppDelegate::GetInstance()->OnTouchBegan(pointX, pointY);
}

JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeOnTouchesEnded(JNIEnv *env, jclass type,
                                                             jfloat pointX, jfloat pointY) {
    LAppDelegate::GetInstance()->OnTouchEnded(pointX, pointY);
}

JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeOnTouchesMoved(JNIEnv *env, jclass type,
                                                             jfloat pointX, jfloat pointY) {
    LAppDelegate::GetInstance()->OnTouchMoved(pointX, pointY);
}
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeProjectChangeTo(JNIEnv *env, jclass clazz,
                                                              jstring model_path,
                                                              jstring model_json_file_name) {
    auto modelPathStr = env->GetStringUTFChars(model_path, nullptr);
    auto modelJsonFileNameStr = env->GetStringUTFChars(model_json_file_name, nullptr);
    LAppDelegate::GetInstance()->ModelChangeTo(modelPathStr, modelJsonFileNameStr);
    env->ReleaseStringUTFChars(model_path, modelPathStr);
    env->ReleaseStringUTFChars(model_json_file_name, modelJsonFileNameStr);
}
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeApplyExpression(JNIEnv *env, jclass clazz,
                                                              jstring expression_name) {
    auto expressionName = env->GetStringUTFChars(expression_name, nullptr);
    LAppDelegate::GetInstance()->ApplyExpression(expressionName);
    env->ReleaseStringUTFChars(expression_name, expressionName);
}
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeApplyOutfit(JNIEnv *env, jclass clazz,
                                                          jstring outfit_name) {
    auto outfitName = env->GetStringUTFChars(outfit_name, nullptr);
    LAppDelegate::GetInstance()->ApplyOutfit(outfitName);
    env->ReleaseStringUTFChars(outfit_name, outfitName);
}
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeClearOutfit(JNIEnv *env, jclass clazz) {
    LAppDelegate::GetInstance()->ClearOutfit();
}
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_needRenderBack(JNIEnv *env, jclass clazz, jboolean back) {
    LAppDelegate::GetInstance()->NeedRenderBack(back == JNI_TRUE);
}
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeProjectScale(JNIEnv *env, jclass clazz,
                                                           jfloat scale) {
    LAppDelegate::GetInstance()->ModelResize(scale);
}
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeProjectTransformX(JNIEnv *env, jclass clazz,
                                                                jfloat transform) {
    LAppDelegate::GetInstance()->ModelTranslateX(transform);
}
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeProjectTransformY(JNIEnv *env, jclass clazz,
                                                                jfloat transform) {
    LAppDelegate::GetInstance()->ModelTranslateY(transform);
}
}
extern "C"
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeAutoBlinkEyes(JNIEnv *env, jclass clazz,
                                                            jboolean enabled) {
    LAppDelegate::GetInstance()->ModelAutoBlinkEyes(enabled == JNI_TRUE);

}
extern "C"
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeProjectMouthForm(JNIEnv *env, jclass clazz,
                                                               jfloat value) {
    LAppDelegate::GetInstance()->ModelMouthForm(value);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeProjectMouthOpenY(JNIEnv *env, jclass clazz,
                                                                jfloat value) {
    LAppDelegate::GetInstance()->ModelMouthOpenY(value);
}

// v26: chatBg 进 GL — Java 端把 drawable 转成 ARGB int[] 喂进来，native 存到
// pending 缓冲，GL thread 在下一次 Render() 里建纹理 + 全屏 quad。
// ARGB int 跟 GL_RGBA/GL_UNSIGNED_BYTE 在 little-endian 上是 [B,G,R,A]，
// 所以 glTexImage2D 要用 GL_RGBA + GL_UNSIGNED_BYTE，源数据按字节序自然就成 ABGR 了，
// 实际渲出来颜色对不上。解法：要么 a 通道在 native 端手工交换位，要么 Java 端
// 直接用 ByteBuffer+Color 转换。这里选了 native 端 swap（O(n)，只在切换背景时跑）。
// 注意：不要加 static —— LAppView.cpp 通过 extern 引用这几个符号消费 pending 像素
Csm::csmUint32* s_pendingPixels = NULL;
int s_pendingW = 0;
int s_pendingH = 0;

static void ReleasePendingPixels() {
    if (s_pendingPixels) {
        delete[] s_pendingPixels;
        s_pendingPixels = NULL;
    }
    s_pendingW = 0;
    s_pendingH = 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeSetBackground(JNIEnv *env, jclass clazz,
                                                             jintArray argb, jint width,
                                                             jint height) {
    if (argb == NULL || width <= 0 || height <= 0) {
        ReleasePendingPixels();
        return;
    }
    jsize len = env->GetArrayLength(argb);
    const int expected = width * height;
    if (len < expected) {
        __android_log_print(ANDROID_LOG_ERROR, "MoeAvatar.Live2D",
            "nativeSetBackground: size mismatch len=%d expected=%d", len, expected);
        return;
    }
    ReleasePendingPixels();
    s_pendingPixels = new Csm::csmUint32[expected];
    env->GetIntArrayRegion(argb, 0, expected, reinterpret_cast<jint*>(s_pendingPixels));
    // ARGB -> ABGR：Android ARGB_8888 的 0xAARRGGBB 写到 GL_UNSIGNED_BYTE + GL_RGBA
    // 时，glTexImage2D 期望的是字节序 [R,G,B,A]，所以 native 端要 swap R<->B。
    for (int i = 0; i < expected; ++i) {
        Csm::csmUint32 p = s_pendingPixels[i];
        Csm::csmUint32 a = (p & 0xFF000000u);
        Csm::csmUint32 r = (p & 0x00FF0000u) >> 16;
        Csm::csmUint32 g = (p & 0x0000FF00u);
        Csm::csmUint32 b = (p & 0x000000FFu) << 16;
        s_pendingPixels[i] = a | r | g | b;
    }
    s_pendingW = width;
    s_pendingH = height;
    __android_log_print(ANDROID_LOG_INFO, "MoeAvatar.Live2D",
        "nativeSetBackground: queued %dx%d", width, height);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_chatwaifu_live2d_JniBridgeJava_nativeSetScissorBottom(JNIEnv *env, jclass clazz,
                                                                jfloat ratio) {
    LAppDelegate::GetInstance()->SetScissorBottom(ratio);
}
