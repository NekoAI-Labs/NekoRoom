package com.moeavatar.llm

import java.io.File

/**
 * 扫描本地模型目录：在 rootDir 下找一层子目录，每个子目录里有 config.json 就视为一个可用模型。
 *
 * 用法：用户把 MNN 导出的模型拷贝到 /sdcard/Download/MoeAvatar/models/<某个名字>/
 *      其中包含 config.json + tokenizer + 权重文件，就能被这里识别。
 */
object ModelScanner {

    data class LocalModel(val name: String, val configPath: String) {
        override fun toString(): String = name
    }

    fun scan(rootDir: String): List<LocalModel> {
        val root = File(rootDir)
        if (!root.isDirectory) return emptyList()
        val children = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return children
            .mapNotNull { child ->
                val cfg = File(child, "config.json")
                if (cfg.isFile) LocalModel(child.name, cfg.absolutePath) else null
            }
            .sortedBy { it.name }
    }
}
