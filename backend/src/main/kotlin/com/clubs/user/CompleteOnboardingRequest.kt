package com.clubs.user

import jakarta.validation.constraints.NotNull

/**
 * Дверь, через которую человек вышел из онбординга: он не объявляет роль, а нажимает кнопку.
 * MEMBER — «Найти клубы в своём городе», ORGANIZER — «Создать клуб и пригласить друзей».
 * В БД не хранится: это метрика намерения, живёт только в INFO-логе завершения.
 */
enum class OnboardingDoor { MEMBER, ORGANIZER }

data class CompleteOnboardingRequest(
    @field:NotNull
    val door: OnboardingDoor
)
