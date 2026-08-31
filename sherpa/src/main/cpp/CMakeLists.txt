cmake_minimum_required(VERSION 3.18.1)
project(sherpa_cxxshim)

# 思路：把 libMNN.so / libMNN_Express.so / libsherpa-mnn-jni.so / libc++_shared.so
# 全部 IMPORTED 进来，让 sherpa_cxxshim.so 的 DT_NEEDED 里同时声明这四个 + STL。
# Android 7+ linker 在 dlopen(libsherpa_cxxshim) 时会沿 NEEDED 链把它们一起加载到
# 同一 namespace —— c++_shared 在场，sherpa-mnn-jni 引用的 std::__ndk1::* 符号
# (regex_error / basic_string 等) 就能正确解析。
#
# 单独 System.loadLibrary("c++_shared") 没用：Android 7+ linker namespace 隔离，
# 必须靠 NEEDED 拽。这跟 MnnAsrTest 的 asrtest_shim 是同一招。
#
# 用 ANDROID_STL=c++_shared 编（在 sherpa/build.gradle defaultConfig 里触发），
# cmake 自动给产物加 NEEDED libc++_shared.so。

set(JNILIBS_APP "${CMAKE_CURRENT_SOURCE_DIR}/../../../../app/src/main/jniLibs/${ANDROID_ABI}")
set(JNILIBS_SELF "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

add_library(mnn SHARED IMPORTED)
set_target_properties(mnn PROPERTIES IMPORTED_LOCATION "${JNILIBS_APP}/libMNN.so")

add_library(mnn_express SHARED IMPORTED)
set_target_properties(mnn_express PROPERTIES IMPORTED_LOCATION "${JNILIBS_APP}/libMNN_Express.so")

add_library(sherpa_mnn SHARED IMPORTED)
set_target_properties(sherpa_mnn PROPERTIES IMPORTED_LOCATION "${JNILIBS_SELF}/libsherpa-mnn-jni.so")

add_library(sherpa_cxxshim SHARED sherpa_cxxshim.cpp)
target_link_libraries(sherpa_cxxshim log mnn mnn_express sherpa_mnn)
