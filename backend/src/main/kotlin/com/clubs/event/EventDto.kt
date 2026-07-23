package com.clubs.event

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class EventDetailDto(
    val id: UUID,
    val clubId: UUID,
    val title: String,
    val description: String?,
    // null = место не указано (опционально с V58) — фронт прячет блок места целиком.
    val locationText: String?,
    // Гео-точка места (WGS-84). null у легаси-событий и событий без места —
    // фронт показывает место текстом без карты (или ничего). Инвариант: оба или ни одного.
    val locationLat: Double?,
    val locationLon: Double?,
    // Опциональное уточнение организатора к месту («Вход со двора, домофон 12»); null = нет.
    val locationHint: String?,
    val eventDatetime: OffsetDateTime,
    // null = открытая встреча (V62): без гонки за места и листа ожидания, фронт прячет знаменатель.
    val participantLimit: Int?,
    val votingOpensDaysBefore: Int,
    // Эффективный интервал Этапа 2 (минут до старта): свой у события или глобальный дефолт —
    // фронт показывает «подтверждение мест за N ч», не хардкодя порог (урок confirmedDeclineDeadline).
    // null = открытая встреча (гонки за места нет, интервал не настраивается; технический флип
    // статуса у неё всё же происходит — по глобальному дефолту).
    val stage2LeadMinutes: Int?,
    val status: String,
    val goingCount: Int,
    val maybeCount: Int,
    val notGoingCount: Int,
    val confirmedCount: Int,
    // Крайний момент, до которого ПОДТВЕРЖДЁННЫЙ участник ещё может отказаться от места
    // (= eventDatetime − events.stage2-decline-cutoff-minutes). Фронт прячет кнопку «Отказаться»
    // у confirmed, когда now ≥ этого значения; бэк остаётся источником истины (declineParticipation
    // отклонит поздний отказ). У waitlisted порога нет. Значение — чистая функция от даты события,
    // поэтому одинаково для всех (не пер-юзер) и живёт в общем DTO. Убирает прежний рассинхрон:
    // фронт держал копию порога хардкодом (4 ч), не связанную с рантайм-env бэка.
    val confirmedDeclineDeadline: OffsetDateTime,
    // Сколько очков репутации спишется за отказ от подтверждённого места без замены в очереди
    // (abandoned_slot, положительное число для текста «спишется N очков»). Источник истины —
    // ReputationPolicy на бэке; фронт НЕ хардкодит величину (тот же урок, что confirmedDeclineDeadline:
    // копия порога на клиенте разъезжается с рантаймом). У открытой встречи штрафа нет — фронт
    // предупреждение не показывает, поле игнорирует.
    val abandonedSlotPenaltyPoints: Int,
    val attendanceMarked: Boolean,
    val attendanceFinalized: Boolean,
    // F5-14: опциональная причина отмены от организатора; null, если отменено без указания причины.
    val cancellationReason: String?,
    val photoUrl: String?,
    val createdAt: OffsetDateTime?
)

data class EventListItemDto(
    val id: UUID,
    val title: String,
    val eventDatetime: OffsetDateTime,
    val locationText: String?,
    // null = открытая встреча (V62) — карточка показывает счёт без знаменателя.
    val participantLimit: Int?,
    val goingCount: Int,
    val status: String,
    val photoUrl: String?
)

data class MyEventListItemDto(
    val id: UUID,
    val title: String,
    val eventDatetime: OffsetDateTime,
    val locationText: String?,
    // Фото события — фон обложки карточки в табе «Активности»; null = фолбэк на аватар клуба.
    val photoUrl: String?,
    val status: String,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val myVote: String?,
    val myParticipationStatus: String?,
    val goingCount: Int,
    val confirmedCount: Int,
    // null = открытая встреча (V62) — карточка показывает счёт без знаменателя.
    val participantLimit: Int?,
    // Срочная встреча (V69) — карточка показывает бейдж «⚡ срочная» вместо «🎟 обычная».
    val isUrgent: Boolean,
    val actionRequired: Boolean,
    // true = прошедшее посещённое событие (секция «История»). Считает бэкенд по бакету ORDER BY.
    // Клиенту ЗАПРЕЩЕНО выводить историчность из status='completed' или eventDatetime<now:
    // статус completed выставляется кроном с запасом 6ч, окно рассинхрона до ~7ч реально (AC-H14).
    val isHistory: Boolean
)

data class CreateEventRequest(
    @field:NotBlank(message = "Title is required")
    @field:Size(max = 255, message = "Title must be at most 255 characters")
    val title: String,

    val description: String? = null,

    // Место опционально (решение PO 2026-07-11, V58): без поиска организаций обязательная
    // гео-точка неудобна. Адрес приходит из обратного геокодера пикера, когда точка выбрана.
    @field:Size(max = 500, message = "Location must be at most 500 characters")
    val locationText: String? = null,

    @field:DecimalMin(value = "-90.0", message = "Latitude must be >= -90")
    @field:DecimalMax(value = "90.0", message = "Latitude must be <= 90")
    val locationLat: Double? = null,

    @field:DecimalMin(value = "-180.0", message = "Longitude must be >= -180")
    @field:DecimalMax(value = "180.0", message = "Longitude must be <= 180")
    val locationLon: Double? = null,

    @field:Size(max = 200, message = "Location hint must be at most 200 characters")
    val locationHint: String? = null,

    @field:NotNull(message = "Event datetime is required")
    @field:Future(message = "Event datetime must be in the future")
    val eventDatetime: OffsetDateTime,

    // null = ОТКРЫТАЯ ВСТРЕЧА (V62): событие без лимита участников — отдельный продуктовый
    // тип поверх того же движка. @Positive пропускает null по контракту Bean Validation,
    // ненулевое значение валидируется как раньше. Инвариант пары с isOpenEvent — ниже.
    @field:Positive(message = "Participant limit must be positive")
    val participantLimit: Int? = null,

    // Явный флаг формата: открытая встреча заявляется НАМЕРЕННО, а не отсутствием participantLimit.
    // Без флага случайно пропущенное поле лимита молча создавало бы событие другого продуктового
    // типа (без гонки за места и репутации за посещение) вместо прежнего 400.
    val isOpenEvent: Boolean = false,

    @field:Min(value = 1, message = "Voting opens days before must be at least 1")
    @field:Max(value = 14, message = "Voting opens days before must be at most 14")
    val votingOpensDaysBefore: Int = 14,

    // За сколько МИНУТ до старта событие переходит в Этап 2 (подтверждение мест) — выбор
    // организатора (V67/V68, решения PO 2026-07-23). null = глобальный дефолт
    // events.stage2-trigger-minutes-before (18 часов). Пресеты фронта: 18ч/36ч/3 дня/5 дней.
    // Встречи «ближе 18 часов» закрывает формат «Срочная встреча» (isUrgentEvent), а не малый
    // интервал. Диапазон зеркалит CHECK chk_events_stage2_lead_minutes (V68).
    @field:Min(value = 1080, message = "Stage 2 lead must be at least 1080 minutes (18 hours)")
    @field:Max(value = 7200, message = "Stage 2 lead must be at most 7200 minutes (5 days)")
    val stage2LeadMinutes: Int? = null,

    // Срочная встреча (решение PO 2026-07-23): обычное событие с местами, но БЕЗ Этапа 1 —
    // рождается сразу в stage_2, участники немедленно подтверждают места. Репутация работает
    // как у обычного события. Явный флаг по тому же принципу, что isOpenEvent.
    val isUrgentEvent: Boolean = false,

    @field:Size(max = 1024, message = "Photo URL must be at most 1024 characters")
    val photoUrl: String? = null
) {
    // Инвариант пары координат (зеркалит CHECK chk_events_location_pair в БД): половинная
    // точка бессмысленна — Bean Validation отдаёт дружелюбный 400 раньше, чем упадёт insert.
    @get:AssertTrue(message = "Latitude and longitude must be provided together")
    val isLocationPairConsistent: Boolean
        get() = (locationLat == null) == (locationLon == null)

    // Требование PO (2026-07-11): у события должно быть хоть какое-то указание места —
    // либо гео-точка с карты, либо текстовое уточнение («в зуме», «место скинем в чат»).
    @get:AssertTrue(message = "Either a map point or a location hint is required")
    val isSomeLocationProvided: Boolean
        get() = (locationLat != null && locationLon != null) || !locationHint.isNullOrBlank()

    // Формат и лимит согласованы: открытая встреча — БЕЗ лимита, обычное событие — С лимитом.
    // Ловит и старый баг-класс «забыли поле» (limit=null без флага → 400, как до V62), и
    // противоречивый ввод (флаг + лимит одновременно).
    @get:AssertTrue(message = "Open event must have no participant limit; a regular event requires one")
    val isParticipantLimitConsistent: Boolean
        get() = if (isOpenEvent) participantLimit == null else participantLimit != null

    // Открытая встреча целиком вне двухэтапки — свой lead Этапа 2 для неё бессмысленен и
    // почти наверняка означает ошибку клиента, а не намерение.
    @get:AssertTrue(message = "Open event has no stage 2; stage2LeadMinutes is not applicable")
    val isStage2LeadConsistent: Boolean
        get() = !isOpenEvent || stage2LeadMinutes == null

    // Срочная встреча = событие с местами (не открытая) и без своего интервала: Этапа 1 нет,
    // поэтому «за сколько до старта переходить в Этап 2» к ней неприменимо.
    @get:AssertTrue(message = "Urgent event must be a limited event without a custom stage 2 lead")
    val isUrgentConsistent: Boolean
        get() = !isUrgentEvent || (!isOpenEvent && stage2LeadMinutes == null)
}


/** F5-14: опциональная причина отмены события от организатора (≤500 символов; пусто → null). */
data class CancelEventRequest(
    @field:Size(max = 500)
    val reason: String? = null
)
