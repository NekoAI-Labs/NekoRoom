package com.moeavatar.perf

import android.os.Build

data class PerformanceDeviceInfo(
    val device: String,
    val soc: String,
    val android: String,
    val abi: String,
    val cores: Int,
    val sme2: String,
    val i8mm: String,
    val dotprod: String,
) {
    fun display(): String = listOf(
        "设备：$device",
        "SoC：$soc",
        "Android：$android",
        "ABI：$abi · CPU 核心：$cores",
        "SME2：$sme2 · i8mm：$i8mm · dotprod：$dotprod",
    ).joinToString("\n")

    companion object {
        fun collect(): PerformanceDeviceInfo {
            // Some vendor kernels restrict /proc/cpuinfo or expose flags with unusual
            // formatting. Device information must never make the performance panel fail.
            val cpuInfo = runCatching { java.io.File("/proc/cpuinfo").readText() }.getOrDefault("")
            val flags = cpuInfo.lowercase()
            fun feature(vararg names: String): String = when {
                flags.isEmpty() -> "未知"
                names.any(flags::contains) -> "支持"
                else -> "不支持"
            }
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() }.joinToString(" ")
            } else "未知"
            return PerformanceDeviceInfo(
                device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                soc = soc.ifBlank { "未知" },
                android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "未知" },
                cores = Runtime.getRuntime().availableProcessors(),
                sme2 = feature("sme2"),
                i8mm = feature("i8mm"),
                dotprod = feature("asimddp", "dotprod"),
            )
        }
    }
}
