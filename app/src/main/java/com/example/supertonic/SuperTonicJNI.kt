package com.example.supertonic

/**
 * JNI contract for the SuperTonic-Neko native inference library (libsupertonic.so).
 *
 * Mirrors MoeAvatar's BertVITS2JNI pattern: an interface + a concrete class whose
 * name (`SuperTonicJNI`) must match the C++ JNI function names
 * (Java_com_example_supertonic_SuperTonicJNI_*).
 *
 * The frontend (text -> ids) is done in Kotlin (see ZhFrontend). This layer only
 * moves the tokenized arrays across JNI and returns PCM float samples.
 */
interface ISuperTonicJNI {

    /** Create the shared MNN executor. Call once before setModelPath. */
    fun initLoader(numThreads: Int)

    /** Load the 4 fp16 .mnn files (absolute paths, e.g. under /sdcard/supertonic-neko/). */
    fun setModelPath(dpMnn: String, teMnn: String, veMnn: String, vocoderMnn: String): Boolean

    /** Release modules + executor. */
    fun destroyLoader()

    /**
     * Full synthesis.
     * @param textIds/pinyinIds/toneIds/prosodyIds actual equal token length, from ZhFrontend
     * @param textMask   actual token length, 0/1 float
     * @param styleTtl   50*256 floats, row-major [50,256] (from catgirl_style.json)
     * @param styleDp    8*16 floats [8,16]
     * @param seed       RNG seed for the initial latent
     * @param speed      duration divisor (1.0 = native pace; >1 faster, <1 slower)
     * @return PCM float samples @ 44.1kHz, or null on error
     */
    fun synth(
        textIds: IntArray,
        pinyinIds: IntArray,
        toneIds: IntArray,
        prosodyIds: IntArray,
        textMask: FloatArray,
        styleTtl: FloatArray,
        styleDp: FloatArray,
        seed: Int,
        speed: Float,
        totalSteps: Int,
    ): FloatArray?

    /** Cooperative cancellation; the native side checks it between diffusion graph runs. */
    fun cancelSynthesis()

    /** Optional: inject a fixed initial latent (144*100 floats) for Torch/MNN parity checks. */
    fun setFixedNoise(noise: FloatArray)

    /** Enable detailed native stage timing logs for developer diagnostics. */
    fun setPerfLogging(enabled: Boolean)
}

class SuperTonicJNI : ISuperTonicJNI {

    external override fun initLoader(numThreads: Int)

    external override fun setModelPath(dpMnn: String, teMnn: String, veMnn: String, vocoderMnn: String): Boolean

    external override fun destroyLoader()

    external override fun synth(
        textIds: IntArray,
        pinyinIds: IntArray,
        toneIds: IntArray,
        prosodyIds: IntArray,
        textMask: FloatArray,
        styleTtl: FloatArray,
        styleDp: FloatArray,
        seed: Int,
        speed: Float,
        totalSteps: Int,
    ): FloatArray?

    external override fun cancelSynthesis()

    external override fun setFixedNoise(noise: FloatArray)

    external override fun setPerfLogging(enabled: Boolean)

    companion object {
        init {
            // Prebuilt MNN shared libs must be in jniLibs/arm64-v8a/ (copy from
            // MoeAvatar/app/src/main/jniLibs/arm64-v8a/ — NDK28-built, ARM82/fp16).
            System.loadLibrary("MNN_Express")
            System.loadLibrary("MNN")
            System.loadLibrary("supertonic")
        }
    }
}
