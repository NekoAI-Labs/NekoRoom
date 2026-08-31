#include <android/log.h>
#include <jni.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <fstream>

#include <MNN/Interpreter.hpp>
#include "firered_frontend/fbank.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FireRedVad", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "FireRedVad", __VA_ARGS__)

namespace {
struct VadSession {
    std::unique_ptr<MNN::Interpreter> interpreter;
    MNN::Session* session = nullptr;
    std::mutex mutex;
    vad::Fbank fbank{80, 16000, 400, 160};
    std::vector<float> means, istd;
};

VadSession* ptr(jlong value) { return reinterpret_cast<VadSession*>(value); }

jfloatArray inferFeatures(JNIEnv* env, VadSession* vad, const float* features, jint frames) {
    if (!vad || frames <= 0 || !features) return nullptr;
    std::lock_guard<std::mutex> guard(vad->mutex);
    auto* input = vad->interpreter->getSessionInput(vad->session, "feat");
    if (!input) return nullptr;
    vad->interpreter->resizeTensor(input, {1, frames, 80});
    vad->interpreter->resizeSession(vad->session);
    // FireRed is [B,T,80]; Caffe layout is mandatory for this converted graph.
    std::unique_ptr<MNN::Tensor> hostInput(MNN::Tensor::create(
        {1, frames, 80}, halide_type_of<float>(), const_cast<float*>(features), MNN::Tensor::CAFFE));
    input->copyFromHostTensor(hostInput.get());
    if (vad->interpreter->runSession(vad->session) != MNN::NO_ERROR) return nullptr;
    auto* output = vad->interpreter->getSessionOutput(vad->session, "probs");
    if (!output) return nullptr;
    MNN::Tensor hostOutput(output, MNN::Tensor::CAFFE);
    output->copyToHostTensor(&hostOutput);
    const int count = hostOutput.elementSize();
    jfloatArray result = env->NewFloatArray(count);
    if (result) env->SetFloatArrayRegion(result, 0, count, hostOutput.host<float>());
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_moeavatar_vad_FireRedVadBridge_initNative(JNIEnv* env, jobject, jstring path, jstring cmvnPath) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    auto result = std::make_unique<VadSession>();
    result->interpreter.reset(MNN::Interpreter::createFromFile(chars));
    env->ReleaseStringUTFChars(path, chars);
    if (!result->interpreter) { LOGE("cannot load model"); return 0; }
    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_CPU;
    config.numThread = 1;
    result->session = result->interpreter->createSession(config);
    if (!result->session) { LOGE("cannot create session"); return 0; }
    const char* cmvn = env->GetStringUTFChars(cmvnPath, nullptr);
    std::ifstream input(cmvn, std::ios::binary);
    env->ReleaseStringUTFChars(cmvnPath, cmvn);
    result->means.resize(80); result->istd.resize(80);
    input.read(reinterpret_cast<char*>(result->means.data()), 80 * sizeof(float));
    input.read(reinterpret_cast<char*>(result->istd.data()), 80 * sizeof(float));
    if (!input) { LOGE("cannot read cmvn"); return 0; }
    LOGI("ready: dynamic FireRed VAD, feature_dim=80");
    return reinterpret_cast<jlong>(result.release());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_moeavatar_vad_FireRedVadBridge_inferPcmNative(
        JNIEnv* env, jobject thiz, jlong handle, jshortArray pcm) {
    auto* vad = ptr(handle);
    if (!vad) return nullptr;
    const jsize count = env->GetArrayLength(pcm);
    std::vector<jshort> raw(count);
    env->GetShortArrayRegion(pcm, 0, count, raw.data());
    std::vector<float> wave(count);
    for (int i = 0; i < count; ++i) wave[i] = raw[i];
    std::vector<float> features;
    vad->fbank.reset();
    const int frames = vad->fbank.Compute(wave, &features);
    if (frames <= 0) return nullptr;
    for (int t = 0; t < frames; ++t) for (int d = 0; d < 80; ++d) {
        const int i = t * 80 + d;
        features[i] = (features[i] - vad->means[d]) * vad->istd[d];
    }
    return inferFeatures(env, vad, features.data(), frames);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_moeavatar_vad_FireRedVadBridge_inferNative(
        JNIEnv* env, jobject, jlong handle, jfloatArray features, jint frames) {
    auto* vad = ptr(handle);
    if (!vad || frames <= 0 || env->GetArrayLength(features) != frames * 80) return nullptr;
    std::vector<float> host(static_cast<size_t>(frames) * 80);
    env->GetFloatArrayRegion(features, 0, static_cast<jsize>(host.size()), host.data());
    return inferFeatures(env, vad, host.data(), frames);
}

extern "C" JNIEXPORT void JNICALL
Java_com_moeavatar_vad_FireRedVadBridge_releaseNative(JNIEnv*, jobject, jlong handle) {
    delete ptr(handle);
}
