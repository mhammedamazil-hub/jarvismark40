package com.jarvis.mobile

/**
 * Runtime configuration that customizes JARVIS globally: the **persona** (a Markdown file
 * you can drop in / edit — it rewrites how the whole AI behaves) and **recalled memory**.
 * [ModelClient] folds both into every system prompt, so changing them changes everything.
 */
object JarvisConfig {
    @Volatile var persona: String = ""     // custom persona markdown ("" = built-in default)
    @Volatile var memory: String = ""      // recalled facts, injected as context

    /** Fold persona (front) + memory (back) around a base system prompt. */
    fun systemPrompt(base: String): String = buildString {
        if (persona.isNotBlank()) { append(persona.trim()); append("\n\n") }
        append(base)
        if (memory.isNotBlank()) { append("\n\nWHAT YOU REMEMBER ABOUT THE USER:\n").append(memory.trim()) }
    }
}
