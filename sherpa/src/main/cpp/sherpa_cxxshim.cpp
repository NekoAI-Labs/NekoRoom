// 唯一目的：让本 .so 在 NEEDED 段里依赖 libc++_shared.so，
// 这样进程在 dlopen 它时 linker 会顺手把 libc++_shared.so 拖进同一 namespace。
// 之后再 dlopen libsherpa-mnn-jni.so（c++_shared 动态链接但没声明 NEEDED 的那个）就能解析到 std::__ndk1::* 符号。
//
// 必须真正引用一些 std::__ndk1::* 符号，否则编译器/链接器会优化掉 c++_shared 依赖。

#include <regex>
#include <string>

extern "C" __attribute__((visibility("default")))
int sherpa_cxxshim_keep_alive() {
    // 触发 std::regex_error 的链接（与 libsherpa-mnn-jni.so 找不到的那个符号一致）
    try {
        std::regex r(".*");
    } catch (const std::regex_error &) {
        return 1;
    }
    std::string s = "ok";
    return static_cast<int>(s.size());
}
