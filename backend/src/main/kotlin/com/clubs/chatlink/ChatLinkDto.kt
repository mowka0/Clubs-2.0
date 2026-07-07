package com.clubs.chatlink

import java.time.OffsetDateTime

/**
 * Статус привязки чата для таба «Чат» в «Управлении клубом» (только владелец).
 * `linked=false` → все поля состояния пустые, фронт показывает состояние A (CTA привязки).
 */
data class ChatLinkStatusDto(
    val linked: Boolean,
    val chatTitle: String?,
    val linkedAt: OffsetDateTime?,
    /** administrator | member | left | kicked — статус бота в чате (null пока не привязан). */
    val botStatus: String?,
    val canPinMessages: Boolean,
    val canInviteUsers: Boolean,
    val doorEnabled: Boolean,
    val doorInviteLink: String?,
    /** Deep link ?startgroup= для кнопки «Привязать чат» (username бота живёт на сервере). */
    val startGroupUrl: String
)

/** Тумблер «Вход в чат через заявки» (дверь). */
data class UpdateChatLinkRequest(
    val doorEnabled: Boolean
)
