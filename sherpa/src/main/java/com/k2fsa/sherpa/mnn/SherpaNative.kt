package com.k2fsa.sherpa.mnn

import android.util.Log

/**
 * sherpa-mnn-jni 的 native 加载入口。
 *
 * libsherpa-mnn-jni.so 自身 DT_NEEDED 没声明 libc++_shared.so，dlopen 时 linker
 * 找不到 std::__ndk1::* 符号会报 `cannot locate symbol _ZNSt6__ndk1...`。
 *
 * 解决办法：先 load libsherpa_cxxshim.so —— 这个 ~280KB 的引子用 c++_shared
 * 编译，它的 NEEDED 段直接列了 c++_shared，dlopen 会把 c++_shared 加进同一个
 * linker namespace，之后再 load 主 .so 就能解析到所有 STL 符号。
 *
 * 跟 MnnAsrTest 的 asrtest_shim 是同一思路。
 */
object SherpaNative {
    private const val TAG = "SherpaNative"

    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        runCatching { System.loadLibrary("sherpa_cxxshim") }
            .onFailure { Log.w(TAG, "loadLibrary sherpa_cxxshim failed: ${it.message}") }
        System.loadLibrary("sherpa-mnn-jni")
        Log.d(TAG, "loaded libsherpa-mnn-jni.so")
        loaded = true
    }
}
