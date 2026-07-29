package com.clubs.activity.dto

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Единый элемент ленты активности клуба — дискриминированное объединение по полю `type`.
 *
 * Дизайн дискриминатора: поле `type` **явно объявлено** в каждом конкретном подтипе с
 * константным строковым значением. Jackson сериализует его как обычное свойство, давая
 * ровно одно поле `"type": "event" | "skladchina"` на выходе.
 *
 * Почему без `@JsonTypeInfo` / `@JsonSubTypes`: этот DTO **только для ответа** — сервер
 * никогда его не десериализует (фронтенд потребляет его как TS discriminated union).
 * Машинерия полиморфной десериализации добавила бы сложность ради фичи, которой мы не
 * пользуемся, а совмещение её с явным `type` val было хрупким (зависело от того, что
 * `EXISTING_PROPERTY` остаётся идемпотентным между версиями Jackson).
 *
 * Живёт в [com.clubs.activity], потому что объединяет два разных домена (события,
 * складчины) и не должен протекать ни в один из их пакетов.
 */
sealed class ActivityItemDto {
    abstract val type: String
    abstract val id: UUID
    abstract val clubId: UUID
    abstract val title: String
    abstract val createdAt: OffsetDateTime
    abstract val isCompleted: Boolean

    data class EventActivity(
        override val id: UUID,
        override val clubId: UUID,
        override val title: String,
        override val createdAt: OffsetDateTime,
        override val isCompleted: Boolean,
        val eventDatetime: OffsetDateTime,
        // null = место не указано (опционально с V58).
        val locationText: String?,
        // null = открытая встреча (V62) — карточка показывает счёт без знаменателя.
        val participantLimit: Int?,
        // Срочная встреча (V69) — карточка показывает бейдж «⚡ Срочная» вместо «🎟 Обычная».
        val isUrgent: Boolean,
        val goingCount: Int,
        // Размер подтверждённого ростера stage-2. Лента показывает `goingCount` во время stage 1
        // и переключается на `confirmedCount`, как только голосование закрывается (stage_2/completed),
        // зеркаля страницу события, чтобы карточка и деталка никогда не расходились (F5-21).
        val confirmedCount: Int,
        val status: String,
        val descriptionPreview: String?,
        val photoUrl: String?,
        // true, когда это событие ждёт от запрашивающего пользователя голоса stage-1 или
        // подтверждения stage-2 — управляет бейджем "Проголосуй"/"Подтверди участие" в ленте.
        val actionRequired: Boolean
    ) : ActivityItemDto() {
        override val type: String = "event"
    }

    data class SkladchinaActivity(
        override val id: UUID,
        override val clubId: UUID,
        override val title: String,
        override val createdAt: OffsetDateTime,
        override val isCompleted: Boolean,
        val paymentMode: String,
        val totalGoalKopecks: Long?,
        val collectedKopecks: Long,
        val deadline: OffsetDateTime,
        val participantCount: Int,
        val paidCount: Int,
        val status: String,
        val affectsReputation: Boolean,
        val photoUrl: String?
    ) : ActivityItemDto() {
        override val type: String = "skladchina"
    }
}
