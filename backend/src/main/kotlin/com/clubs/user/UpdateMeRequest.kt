package com.clubs.user

import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Полная замена редактируемых пользователем полей профиля (форма редактирования всегда
 * присылает своё полное состояние). Пустые cityId/bio очищают поле; [interests]
 * нормализуется и дедуплицируется на сервере. Имя/аватар/@username здесь НЕТ — они
 * синхронизируются из Telegram при каждой авторизации и были бы перезаписаны.
 *
 * Страну клиент не присылает: она приезжает из справочника вместе с городом (city-dictionary.md).
 */
data class UpdateMeRequest(
    /** Город из справочника `cities`. null = очистить город в профиле. */
    val cityId: UUID? = null,

    @field:Size(max = 280)
    val bio: String? = null,

    val interests: List<String> = emptyList()
)
