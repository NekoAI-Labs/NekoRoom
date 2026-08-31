//
// JNI bridge for SuperTonic-Neko. Mirrors MoeAvatar/bertvits2's bertvits2_jni.cpp.
//
// Kotlin side: class com.example.supertonic.SuperTonicJNI with matching
// external fun signatures (see kotlin/SuperTonicJNI.kt).
//
#include <jni.h>
#include <string>
#include <vector>
#include "super_tonic_loader.hpp"

extern "C" JNIEXPORT void JNICALL
Java_com_example_supertonic_SuperTonicJNI_initLoader(JNIEnv*, jobject, jint numThreads) {
    SUPERTONIC_NEKO::init_loader(numThreads);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_supertonic_SuperTonicJNI_destroyLoader(JNIEnv*, jobject) {
    SUPERTONIC_NEKO::destroy_loader();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_supertonic_SuperTonicJNI_setModelPath(JNIEnv* env, jobject,
                                                       jstring dp, jstring te,
                                                       jstring ve, jstring voc) {
    const char* dpC = env->GetStringUTFChars(dp, nullptr);
    const char* teC = env->GetStringUTFChars(te, nullptr);
    const char* veC = env->GetStringUTFChars(ve, nullptr);
    const char* vocC = env->GetStringUTFChars(voc, nullptr);
    const bool ok = SUPERTONIC_NEKO::set_model_path(dpC, teC, veC, vocC);
    env->ReleaseStringUTFChars(dp, dpC);
    env->ReleaseStringUTFChars(te, teC);
    env->ReleaseStringUTFChars(ve, veC);
    env->ReleaseStringUTFChars(voc, vocC);
    return ok ? JNI_TRUE : JNI_FALSE;
}

static std::vector<int> toIntVec(JNIEnv* env, jintArray arr) {
    jsize n = env->GetArrayLength(arr);
    jint* p = env->GetIntArrayElements(arr, nullptr);
    std::vector<int> v(p, p + n);
    env->ReleaseIntArrayElements(arr, p, JNI_ABORT);
    return v;
}

static std::vector<float> toFloatVec(JNIEnv* env, jfloatArray arr) {
    jsize n = env->GetArrayLength(arr);
    jfloat* p = env->GetFloatArrayElements(arr, nullptr);
    std::vector<float> v(p, p + n);
    env->ReleaseFloatArrayElements(arr, p, JNI_ABORT);
    return v;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_supertonic_SuperTonicJNI_synth(JNIEnv* env, jobject,
                                                jintArray textIds, jintArray pinyinIds,
                                                jintArray toneIds, jintArray prosodyIds,
                                                jfloatArray textMask, jfloatArray styleTtl,
                                                jfloatArray styleDp, jint seed, jfloat speed,
                                                jint totalSteps) {
    std::vector<float> pcm = SUPERTONIC_NEKO::synth(
        toIntVec(env, textIds), toIntVec(env, pinyinIds),
        toIntVec(env, toneIds), toIntVec(env, prosodyIds),
        toFloatVec(env, textMask), toFloatVec(env, styleTtl),
        toFloatVec(env, styleDp), (int)seed, (float)speed, (int)totalSteps);

    jfloatArray out = env->NewFloatArray((jsize)pcm.size());
    if (out == nullptr) return nullptr;
    env->SetFloatArrayRegion(out, 0, (jsize)pcm.size(), pcm.data());
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_supertonic_SuperTonicJNI_cancelSynthesis(JNIEnv*, jobject) {
    SUPERTONIC_NEKO::cancel_synth();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_supertonic_SuperTonicJNI_setFixedNoise(JNIEnv* env, jobject, jfloatArray noise) {
    SUPERTONIC_NEKO::set_fixed_noise(toFloatVec(env, noise));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_supertonic_SuperTonicJNI_setPerfLogging(JNIEnv*, jobject, jboolean enabled) {
    SUPERTONIC_NEKO::set_perf_logging(enabled == JNI_TRUE);
}
