package com.moeavatar

import java.util.concurrent.locks.ReentrantLock

/**
 * 全局 libMNN 互斥锁。
 *
 * ASR (sherpa-mnn OnlineRecognizer.decode) / TTS (BertVITS2 infer.infer) / LLM (qwen forward)
 * 都 link 同一份 libMNN.so / libMNN_Express.so，共享 ThreadPool 与 allocator。
 * 任意两个 native 调用并发跑就会破坏 MNN session 内部状态 → SIGSEGV。
 *
 * 这个锁把所有 libMNN native 调用串行化：
 *   - ASR decode loop 在 ChatAsrController.startContinuousRecording 里每次 decode 持锁
 *   - TTS infer 在 LocalBertVITS2TtsBackend.synth 里持锁（包在 NonCancellable 里，
 *     防止 synthJob cancel 时锁被 finally 释放但 infer 已经进去）
 *
 * ASR 是 Thread（不是协程），用 lock()/unlock()；TTS 是协程，用 withLock。
 * ReentrantLock 可重入，同一线程多次 lock 不会死锁。
 *
 * 持锁时间：
 *   - ASR decode：单次 <100ms（100ms 音频片），快速释放
 *   - TTS infer：~1-2s（一整句合成），长时间持有
 *
 * 当 TTS infer 持锁 2s 时，ASR decode loop 阻塞 2s。期间 AudioRecord 环形缓冲被覆盖，
 * 但用户正在听 TTS 不会说话，丢失的音频无关紧要。TTS 释放后 ASR 立刻恢复 decode。
 * 这比"两个 native 调用并发 → SIGSEGV → 闪退"好得多。
 */
object MnnGlobalLock {
    val lock: ReentrantLock = ReentrantLock()
}
