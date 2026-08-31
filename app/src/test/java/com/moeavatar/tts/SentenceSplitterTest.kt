package com.moeavatar.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun primaryPunctuationKeepsCompleteClauses() {
        val splitter = SentenceSplitter()
        assertEquals(listOf("主人回来啦！", "猫娘等你很久了喵。"), splitter.feed("主人回来啦！猫娘等你很久了喵。"))
    }

    @Test
    fun secondaryPunctuationDoesNotCutShortFragments() {
        val splitter = SentenceSplitter()
        assertEquals(emptyList<String>(), splitter.feed("你好，"))
        assertEquals(listOf("你好，今天我们一起去公园散散步好吗，"), splitter.feed("今天我们一起去公园散散步好吗，"))
    }

    @Test
    fun hardLimitPreventsUnboundedBuffer() {
        val splitter = SentenceSplitter()
        // maxHard=56：57 字硬切出 56，剩余 1 字由 flush 吐出。
        val result = splitter.feed("啊".repeat(57))
        assertEquals(56, result.single().length)
        assertEquals("啊", splitter.flush().single())
    }

    @Test
    fun twentyNineCharsAreNotCutPrematurely() {
        val splitter = SentenceSplitter()
        // 29 字远低于硬切上限（56），不应被切；全部保留到 flush。
        assertTrue(splitter.feed("啊".repeat(29)).isEmpty())
        assertEquals("啊".repeat(29), splitter.flush().single())
    }
}
