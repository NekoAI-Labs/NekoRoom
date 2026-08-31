package com.moeavatar.perf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PerformanceRecord(
    val time: Long,
    val model: String,
    val tokens: Int,
    val llmFirstTokenMs: Long?,
    val llmTotalMs: Long?,
    val ttsFirstPacketMs: Long?,
    val ttsRtf: Double?,
) {
    fun display(): String = buildString {
        append(model)
        if (llmFirstTokenMs != null) append(" · LLM 首字 ${llmFirstTokenMs}ms")
        if (llmTotalMs != null) append(" · LLM 总计 ${llmTotalMs}ms")
        if (ttsFirstPacketMs != null) append(" · TTS 首包 ${ttsFirstPacketMs}ms")
        if (ttsRtf != null) append(" · RTF ${"%.2f".format(ttsRtf)}")
    }

    fun json() = JSONObject().apply {
        put("time", time); put("model", model); put("tokens", tokens)
        put("llm_first", llmFirstTokenMs ?: JSONObject.NULL)
        put("llm_total", llmTotalMs ?: JSONObject.NULL)
        put("tts_first", ttsFirstPacketMs ?: JSONObject.NULL)
        put("tts_rtf", ttsRtf ?: JSONObject.NULL)
    }

    companion object {
        fun from(json: JSONObject) = PerformanceRecord(
            json.optLong("time"), json.optString("model"), json.optInt("tokens"),
            json.optLongOrNull("llm_first"), json.optLongOrNull("llm_total"),
            json.optLongOrNull("tts_first"), json.optDoubleOrNull("tts_rtf"),
        )
        private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
        private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key)
    }
}

class PerformanceRecordStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("moeavatar_performance", Context.MODE_PRIVATE)

    fun recent(): List<PerformanceRecord> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { PerformanceRecord.from(array.getJSONObject(it)) }.reversed()
    }.getOrDefault(emptyList())

    fun append(record: PerformanceRecord) {
        // recent() is newest-first for display; restore chronological order before
        // appending so that takeLast(3) really means the latest three turns.
        val records = (recent().asReversed() + record).takeLast(MAX_RECORDS)
        val array = JSONArray()
        records.forEach { array.put(it.json()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun summary(records: List<PerformanceRecord> = recent()): String {
        if (records.isEmpty()) return ""
        fun avg(values: List<Long>) = values.average().toLong()
        val llm = records.mapNotNull { it.llmFirstTokenMs }
        val tts = records.mapNotNull { it.ttsFirstPacketMs }
        val rtf = records.mapNotNull { it.ttsRtf }
        return buildString {
            append("平均：")
            if (llm.isNotEmpty()) append("LLM 首字 ${avg(llm)}ms")
            if (tts.isNotEmpty()) append(" · TTS 首包 ${avg(tts)}ms")
            if (rtf.isNotEmpty()) append(" · RTF ${"%.2f".format(rtf.average())}")
        }
    }

    companion object { private const val KEY = "recent_records"; private const val MAX_RECORDS = 3 }
}
