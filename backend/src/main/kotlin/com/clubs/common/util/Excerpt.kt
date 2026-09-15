package com.clubs.common.util

/**
 * Описание сбора в чат-посте и DM (PO 2026-09-14): целиком, но не длиннее [DESCRIPTION_MAX]
 * символов — на обрыве многоточие. Форма принимает до 2 000, а пост в чате должен читаться с экрана.
 */
object Excerpt {
    const val DESCRIPTION_MAX = 500

    fun of(text: String?, max: Int = DESCRIPTION_MAX): String? {
        val t = text?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }
}
