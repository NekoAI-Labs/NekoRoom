cmake_minimum_required(VERSION 3.22.1)
project("supertonic")
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# This mirrors MoeAvatar/app/src/main/cpp/bertvits2/CMakeLists.txt.
#
# The parent CMakeLists (your app's top-level cpp/CMakeLists.txt) is expected to:
#   - include_directories(${MNN_REPO_ROOT}/include)   # MNN/expr/*.hpp
#   - declare IMPORTED targets MNN and MNN_Express pointing at the prebuilt
#     libMNN.so / libMNN_Express.so in jniLibs/${ANDROID_ABI}
# then add_subdirectory(supertonic).
#
# If you build this module standalone, define MNN_REPO_ROOT and the two IMPORTED
# targets before add_subdirectory, exactly like MoeAvatar's top-level file.

add_library(${PROJECT_NAME} SHARED
        super_tonic_jni.cpp
        super_tonic_loader.cpp
)

# 16KB page size alignment — required for Android 15+ / arm64 devices.
target_link_options(${PROJECT_NAME} PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")

target_link_libraries(${PROJECT_NAME}
        android
        log
        MNN
        MNN_Express
)
