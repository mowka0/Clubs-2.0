package com.clubs.user

import java.util.UUID

data class UserDto(
    val id: UUID,
    val telegramId: Long,
    val telegramUsername: String?,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    // `city`/`country` — денормализованные из справочника, только для показа. Источник правды — cityId.
    val city: String?,
    val country: String?,
    // null = город не указан или это легаси-профиль, чей город не распознался миграцией V74.
    val cityId: UUID? = null,
    val bio: String?,
    /**
     * Туры онбординга, пройденные этим пользователем. Едет прямо в профиле, а не отдельным
     * запросом: гейт интро и все подсказки нужны фронту сразу на старте, а туров единицы.
     * Пустое множество — новичок, не видевший ещё ничего.
     */
    val onboardingTours: Set<OnboardingTour>
)
