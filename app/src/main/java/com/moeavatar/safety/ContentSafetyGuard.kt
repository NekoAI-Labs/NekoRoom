package com.moeavatar.safety

import java.text.Normalizer

/** Pure text matcher. Production rules are loaded from assets by [ContentSafetyRuleLoader]. */
class ContentSafetyGuard(private val rules: List<Rule>) {

    private val normalizedRules = rules.map { rule ->
        rule.copy(phrases = rule.phrases.map(::normalize).filter(String::isNotEmpty))
    }

    enum class Category(
        val assetPath: String,
        val safeResponse: String,
    ) {
        POLITICAL_SENSITIVE(
            "safety/political_sensitive.txt",
            "这个话题暂时不能陪你继续聊，我们换一个轻松安全的话题吧。",
        ),
        SEXUAL(
            "safety/sexual.txt",
            "这个话题暂时不能陪你继续聊，我们换一个轻松安全的话题吧。",
        ),
        VIOLENCE(
            "safety/violence.txt",
            "这个话题暂时不能陪你继续聊，我们换一个轻松安全的话题吧。",
        ),
        ILLEGAL_INSTRUCTIONS(
            "safety/illegal_instructions.txt",
            "这个话题暂时不能陪你继续聊，我们换一个轻松安全的话题吧。",
        ),
        SELF_HARM(
            "safety/self_harm.txt",
            "听起来你现在可能很难受。请先联系一位你信任的人陪着你；如果有立即危险，请联系当地紧急服务。",
        ),
    }

    data class Rule(
        val category: Category,
        val phrases: List<String>,
    )

    data class Decision(
        val allowed: Boolean,
        val category: Category? = null,
        val safeResponse: String? = null,
    )

    fun check(text: String): Decision {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return ALLOW
        val rule = normalizedRules.firstOrNull { candidate ->
            candidate.phrases.any(normalized::contains)
        } ?: return ALLOW
        return Decision(
            allowed = false,
            category = rule.category,
            safeResponse = rule.category.safeResponse,
        )
    }

    private fun normalize(text: String): String {
        val compatibilityForm = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        return buildString(compatibilityForm.length) {
            compatibilityForm.forEach { ch ->
                if (ch.isLetterOrDigit()) append(ch)
            }
        }
    }

    companion object {
        private val ALLOW = Decision(allowed = true)
    }
}
