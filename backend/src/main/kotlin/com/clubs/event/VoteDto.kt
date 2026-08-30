package com.clubs.event

import java.time.OffsetDateTime
import java.util.UUID

data class CastVoteRequest(
    val vote: String  // "going" | "maybe" | "not_going"
)

data class VoteResponseDto(
    val eventId: UUID,
    val vote: String,
    val goingCount: Int,
    val maybeCount: Int,
    val notGoingCount: Int
)

data class MyVoteDto(
    val vote: String?,
    /**
     * Место участника в составе встречи с порогом набора (V83): `confirmed` — в составе,
     * `waitlisted` — в очереди, null — вне набора или формат без порога. Отдаётся ОТДЕЛЬНО от
     * [vote], потому что на наборе это разные вещи: голос «Иду» подсвечивает кнопку и держит
     * человека во вкладке «Идут», а место отвечает на вопрос «я вообще прохожу?» — при полном
     * составе тот же голос «Иду» кладёт в очередь, и не сказать об этом нельзя.
     */
    val seat: String? = null
)

/**
 * Один проголосовавший в списке события "кто идёт".
 * [status] — текущее намерение пользователя: финальный статус Этапа 2, если он есть
 * (confirmed | waitlisted | declined), иначе голос Этапа 1
 * (going | maybe | not_going).
 * [attendance] — отметка после события, как только организатор её проставил
 * (attended | absent | disputed), иначе null. Управляет UI оспаривания: отсутствующий
 * участник может оспорить отметку; организатор разрешает оспоренную.
 */
data class EventResponderDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val status: String,
    val attendance: String?,
    // Опциональная свободная заметка, которую оставил участник при оспаривании (показывается организатору).
    val disputeNote: String?,
    // Username в Telegram (без @) — чтобы менеджер мог открыть личный чат с молчуном на Этапе 2.
    // NULL и для тех, у кого username не задан, и для НЕ-менеджеров: обычному участнику клуба
    // контакты соседей по событию не отдаём (см. event-stage2-composition.md § «Права»).
    val telegramUsername: String?,
    // Когда менеджер отправил напоминание ответить (null = не напоминали) — гасит колокольчик.
    // Заполняется только в списке «Без ответа»; в общем ростере всегда null.
    val remindedAt: OffsetDateTime? = null
)

/** Итог нажатия «Напомнить» / «Напомнить всем»: сколько напоминаний реально ушло. */
data class RemindResultDto(
    val remindedCount: Int
)

/** Тело запроса напоминания: конкретный участник либо `null` — «все, кому ещё можно». */
data class RemindRequest(
    val userId: UUID? = null
)
