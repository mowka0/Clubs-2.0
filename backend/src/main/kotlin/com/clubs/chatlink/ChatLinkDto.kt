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
    val canRestrictMembers: Boolean,
    val canPromoteMembers: Boolean,
    val doorEnabled: Boolean,
    val doorInviteLink: String?,
    val livePinEnabled: Boolean,
    val skladchinaStatusEnabled: Boolean,
    val strictModeEnabled: Boolean,
    val awardTitlesEnabled: Boolean,
    /** Deep link ?startgroup= для кнопки «Привязать чат» (username бота живёт на сервере). */
    val startGroupUrl: String
)

/**
 * Тумблеры фич чата — частичный PATCH: меняются только присланные поля
 * (doorEnabled — «Вход в чат через заявки», livePinEnabled — «Живой закреп»,
 * skladchinaStatusEnabled — «Статус сборов в чате», strictModeEnabled — «Строгий режим»).
 */
data class UpdateChatLinkRequest(
    val doorEnabled: Boolean? = null,
    val livePinEnabled: Boolean? = null,
    val skladchinaStatusEnabled: Boolean? = null,
    val strictModeEnabled: Boolean? = null,
    val awardTitlesEnabled: Boolean? = null
)
