package com.loopermallee.moncchichi.core.llm

object ModelCatalog {
    val presets = listOf(
        "gpt-4o-mini",
        "gpt-4o",
        "gpt-4-turbo",
        "gpt-3.5-turbo"
    )

    fun defaultModel(): String = presets.first()
}
