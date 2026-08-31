package com.moeavatar.safety

import android.content.Context

/** Loads the versioned internal-test vocabulary from app assets. */
object ContentSafetyRuleLoader {
    fun load(context: Context): ContentSafetyGuard {
        val rules = ContentSafetyGuard.Category.values().map { category ->
            val phrases = context.assets.open(category.assetPath).bufferedReader().useLines { lines ->
                lines.map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
            ContentSafetyGuard.Rule(category, phrases)
        }
        return ContentSafetyGuard(rules)
    }
}
