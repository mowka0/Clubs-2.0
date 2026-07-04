package com.clubs.user

import jakarta.validation.constraints.Size

/**
 * Полная замена редактируемых пользователем полей профиля (форма редактирования всегда
 * присылает своё полное состояние). Пустые country/city/bio очищают поле; [interests]
 * нормализуется и дедуплицируется на сервере. Имя/аватар/@username здесь НЕТ — они
 * синхронизируются из Telegram при каждой авторизации и были бы перезаписаны.
 */
data class UpdateMeRequest(
    @field:Size(max = 8)
    val country: String? = null,

    @field:Size(max = 255)
    val city: String? = null,

    @field:Size(max = 280)
    val bio: String? = null,

    val interests: List<String> = emptyList()
)
