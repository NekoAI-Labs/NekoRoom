//
// SuperTonic-Neko on-device inference loader (MNN Express).
//
// Pipeline: DP -> TE (parallel_pinyin) -> VE (8-step Euler) -> Vocoder -> PCM.
// Mirrors the Python harness in deploy_android/scripts/accuracy_benchmark.py.
//
// Design follows MoeAvatar/bertvits2's bert_vits2_v23_loader: a small C-style
// namespace API (init / set_model_path / synth / destroy) that a JNI bridge
// wraps for Kotlin. The frontend (text -> text_ids/pinyin_ids/tone_ids/
// prosody_ids/text_mask) is done on the Kotlin side; this C++ layer receives
// the already-tokenized int arrays plus the style vectors.
//
#ifndef SUPERTONIC_NEKO_LOADER_HPP
#define SUPERTONIC_NEKO_LOADER_HPP

#include <android/log.h>
#include <string>
#include <vector>

#define STNPRINT(fmt, ...) __android_log_print(ANDROID_LOG_INFO, "SuperTonicJNI", fmt, ##__VA_ARGS__)

namespace SUPERTONIC_NEKO {

// Safety caps; actual text/latent dimensions are derived per utterance.
constexpr int MAX_TEXT = 256;
// 模型按 ~30s 训练（≈ 430 个 latent 帧，30 / (3072/44100) ≈ 430）。
// 旧值 100 帧 = 6.97s，会把长句子的 DP 时长钳死并截断音频（“29 字就没了”的根因）。
// 提到 450 帧 ≈ 31.3s，覆盖模型训练能力；切句器最坏 56 字 ≈ 14.5s，留足余量。
constexpr int MAX_L_TTL = 450;
constexpr int TOTAL_STEP = 8;
constexpr int SR = 44100;
constexpr int LATENT_DIM = 144;   // VE latent channels
constexpr int TEXT_EMB_DIM = 256; // TE output channels
constexpr int CHUNK_SAMPLES = 3072; // wav samples per latent frame (base_chunk 512 * compress 6)
constexpr int STYLE_TTL_TOK = 50;
constexpr int STYLE_TTL_DIM = 256;
constexpr int STYLE_DP_A = 8;
constexpr int STYLE_DP_B = 16;

// Create the shared MNN executor (CPU, fp16-capable). Idempotent.
void init_loader(int num_threads = 4);

// Release all loaded modules + executor.
void destroy_loader();

// Load the four .mnn files. Call after init_loader(). Re-callable to swap models.
bool set_model_path(const std::string& dp_mnn,
                    const std::string& te_mnn,
                    const std::string& ve_mnn,
                    const std::string& vocoder_mnn);

// Full synthesis.
//   text_ids/pinyin_ids/tone_ids/prosody_ids : actual equal token length, int32
//   text_mask                                : actual token length, float 0/1
//   style_ttl                                : STYLE_TTL_TOK*STYLE_TTL_DIM floats (row-major [50,256])
//   style_dp                                 : STYLE_DP_A*STYLE_DP_B floats ([8,16])
//   seed                                     : RNG seed for the initial latent (repro/parity)
//   speed                                    : duration divisor (1.0 = native pace; >1 faster, <1 slower)
// Returns PCM float samples at 44.1kHz. Empty on error.
std::vector<float> synth(const std::vector<int>& text_ids,
                         const std::vector<int>& pinyin_ids,
                         const std::vector<int>& tone_ids,
                         const std::vector<int>& prosody_ids,
                         const std::vector<float>& text_mask,
                         const std::vector<float>& style_ttl,
                         const std::vector<float>& style_dp,
                         int seed,
                         float speed = 1.0f,
                         int total_steps = 8);

// Thread-safe cooperative cancellation. Checked between MNN graph executions;
// never kills the inference thread or releases its sessions mid-run.
void cancel_synth();

// Optional: inject an explicit initial latent (LATENT_DIM*MAX_L_TTL floats) instead
// of sampling from seed — used for Torch/MNN parity checks. Pass empty to disable.
void set_fixed_noise(const std::vector<float>& noise);

// Toggle detailed stage timing logs without changing inference behavior.
void set_perf_logging(bool enabled);

}  // namespace SUPERTONIC_NEKO

#endif  // SUPERTONIC_NEKO_LOADER_HPP
