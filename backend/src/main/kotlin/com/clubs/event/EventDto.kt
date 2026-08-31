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
    // фронт показывает «набор закрывается за N ч», не хардкодя порог: пороги считает бэкенд.
    // null = открытая встреча (гонки за места нет, интервал не настраивается; технический флип
    // статуса у неё всё же происходит — по глобальному дефолту).
    val stage2LeadMinutes: Int?,
    // СОБСТВЕННЫЙ интервал события: null = «используется глобальный дефолт». В отличие от
    // stage2LeadMinutes выше (эффективного, с подставленным дефолтом) это то, что реально
    // лежит в БД, — форма редактирования шлёт обратно именно его, иначе подставленный дефолт
    // молча стал бы собственным значением события.
    val stage2LeadMinutesOverride: Int?,
    val status: String,
    // Формат встречи (V85) — единственный дискриминатор для клиента: и бейдж в шапке, и тексты
    // блока набора, и цена отказа читаются по нему, а не по комбинации лимита с флагами.
    val format: EventFormat,
    val goingCount: Int,
    val maybeCount: Int,
    val notGoingCount: Int,
    val confirmedCount: Int,
    // Сколько участников клуба ещё не ответили на Этапе 2 (все с доступом, кроме сказавших
    // «не пойду» и уже ответивших). Только счётчик — имена отдаёт менеджерский /pending.
    val noAnswerCount: Int,
    // --- Набор состава (V85) ---
    // Момент закрытия набора (= eventDatetime − эффективный stage2LeadMinutes); null у формата
    // «сколько придёт», у которого набора нет. Считает бэкенд: у фронта нет глобального дефолта.
    val rosterDeadline: OffsetDateTime?,
    // Состав закрыт: голоса больше не меняют его, встреча состоится. У «сколько придёт» всегда false.
    val rosterClosed: Boolean,
    // Размер очереди — плитка «В очереди» и текст «вас заменит первый из очереди». Очередь бывает
    // только у MAX: у MIN верхней границы нет, у ANY нет и лимита.
    val waitlistedCount: Int,
    // Сколько очков спишется, если участник ИЗ СОСТАВА откажется прямо сейчас (0 = бесплатно).
    // Цену считает сервер по RosterPolicy: клиент не выводит её из четырёх условий (тот же урок,
    // что stage2LeadMinutes). Для waitlisted не применяется — выход из очереди бесплатен.
    val declineCostPoints: Int,
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
    // Формат для бейджа карточки («🎯 не меньше N» / «🎟 не больше N» / «🌊 сколько придёт»).
    val format: EventFormat,
    // null = формат «сколько придёт» — карточка показывает счёт без знаменателя.
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
    // null = формат «сколько придёт» — карточка показывает счёт без знаменателя.
    val participantLimit: Int?,
    // Формат для бейджа карточки — тот же словарь, что на всех лентах.
    val format: EventFormat,
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

    // Число участников; смысл задаёт format. null = «сколько придёт». @Positive пропускает null
    // по контракту Bean Validation, ненулевое значение валидируется как раньше. Инвариант пары
    // с format — ниже.
    @field:Positive(message = "Participant limit must be positive")
    val participantLimit: Int? = null,

    // Формат встречи — ЕДИНСТВЕННОЕ поле, отвечающее на вопрос «сколько человек нужно» (V85).
    // Раньше на него отвечали два независимых булевых флага (isOpenEvent + isUrgentEvent), и
    // держать их непротиворечивыми приходилось тремя @AssertTrue. Формат заявляется НАМЕРЕННО,
    // а не выводится из отсутствия лимита: пропущенное поле лимита должно давать 400, а не
    // молча создавать событие другого продуктового типа.
    @field:NotNull(message = "Event format is required")
    val format: EventFormat,

    @field:Min(value = 1, message = "Voting opens days before must be at least 1")
    @field:Max(value = 14, message = "Voting opens days before must be at most 14")
    val votingOpensDaysBefore: Int = 14,

    // За сколько МИНУТ до старта закрывается НАБОР СОСТАВА — выбор организатора (V67/V68,
    // с V83 поле несёт смысл «дедлайн набора»). null = глобальный дефолт
    // events.stage2-trigger-minutes-before (18 часов). Пресеты фронта: 6ч/12ч/18ч/36ч/3 дня.
    // Встречу, до которой осталось меньше минимального интервала, формат MAX принимает как есть
    // (дедлайн уже в прошлом → состав закроется ближайшим тиком), а MIN отвергает: иначе тот же
    // тик отменил бы её, не дав никому проголосовать (EventService.createEvent).
    // Диапазон ЗДЕСЬ уже, чем CHECK chk_events_stage2_lead_minutes (60..7200, V83).
    @field:Min(value = 360, message = "Stage 2 lead must be at least 360 minutes (6 hours)")
    @field:Max(value = 7200, message = "Stage 2 lead must be at most 7200 minutes (5 days)")
    val stage2LeadMinutes: Int? = null,

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

    // Формат и лимит — один факт, записанный дважды: «сколько придёт» БЕЗ лимита, остальные С
    // лимитом. Ловит и старый баг-класс «забыли поле лимита», и противоречивый ввод.
    @get:AssertTrue(message = "Format 'any' must have no participant limit; other formats require one")
    val isParticipantLimitConsistent: Boolean
        get() = (format == EventFormat.ANY) == (participantLimit == null)

    // У «сколько придёт» набора нет — свой интервал для неё бессмысленен и почти наверняка
    // означает ошибку клиента, а не намерение.
    @get:AssertTrue(message = "Format 'any' has no roster; stage2LeadMinutes is not applicable")
    val isStage2LeadConsistent: Boolean
        get() = format != EventFormat.ANY || stage2LeadMinutes == null
}


/** F5-14: опциональная причина отмены события от организатора (≤500 символов; пусто → null). */
data class CancelEventRequest(
    @field:Size(max = 500)
    val reason: String? = null
)

/**
 * Тизер-афиша клуба (решение PO 2026-07-24): урезанная проекция событий для смотрящего БЕЗ
 * доступа к контенту (гость или участник без взноса — frozen/expired). Показывает, что клуб
 * живой, — главный аргумент вступить/оплатить. ПО ПОСТРОЕНИЮ не содержит приватного:
 * ни места (locationText/lat/lon/hint), ни фото, ни состава участников — только название,
 * дата, формат и счётчик. Ограничение видимости места — решение PO «Discovery только по
 * клубам» (safety): место встречи не светится никому без доступа.
 */
data class ClubEventsTeaserDto(
    // Ближайшие встречи (будущие, не отменённые), ближайшая первой.
    val upcoming: List<TeaserEventDto>,
    // Последние прошедшие (не отменённые), недавняя первой.
    val past: List<TeaserEventDto>,
    // Сколько встреч клуб провёл за всё время (та же семантика, что «Встреча №N» в чат-итоге).
    val totalPastCount: Int
)

data class TeaserEventDto(
    val id: UUID,
    val title: String,
    val eventDatetime: OffsetDateTime,
    val status: String,
    // Формат для бейджа — как на карточках ленты.
    val format: EventFormat,
    // Число участников: смысл задаёт format, null у «сколько придёт».
    val participantLimit: Int?,
    // Раскладка фазы для счётчика: до Этапа 2 — «идут N» (голоса), после — «подтвердили N».
    val goingCount: Int,
    val confirmedCount: Int
)

/**
 * Полное редактирование встречи организатором (решение PO 2026-07-26). Окно то же, что у
 * переноса: ТОЛЬКО Этап 1 — с началом подтверждения мест правки запрещены, подтвердившие
 * обещали прийти в конкретное место и время.
 *
 * Семантика PUT: клиент присылает ПОЛНЫЙ набор редактируемых полей (форма редактирования
 * загружает текущие значения), поэтому null означает «очистить», а не «не менять». Это
 * избавляет от трёхзначной логики partial-update, где null неотличим от «поле не прислали».
 *
 * НЕ редактируется:
 * - формат встречи — он определяет механику мест и репутации,
 *   смена формата на лету переписала бы правила уже идущего голосования;
 * - `votingOpensDaysBefore` — окно Этапа 1 уже открыто, менять его задним числом бессмысленно.
 *
 * Инварианты, зависящие от формата (лимит у открытой, свой интервал Этапа 2 у открытой и
 * без лимита), проверяются в [EventService.updateEvent]: здесь формат неизвестен, он берётся
 * из самого события.
 */
data class UpdateEventRequest(
    @field:NotBlank(message = "Title is required")
    @field:Size(max = 255, message = "Title must be at most 255 characters")
    val title: String,

    val description: String? = null,

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

    // null = открытая встреча (формат события неизменяем, поэтому согласованность с ним
    // проверяет Service). Для события с местами лимит обязателен и положителен.
    @field:Positive(message = "Participant limit must be positive")
    val participantLimit: Int? = null,

    // Нижняя граница — пресет формы (6 ч); CHECK в БД шире (60..7200, V83), потому что продление
    // набора из DM организатору умеет опускать интервал ниже пресетов. null = глобальный дефолт.
    @field:Min(value = 360, message = "Stage 2 lead must be at least 360 minutes (6 hours)")
    @field:Max(value = 7200, message = "Stage 2 lead must be at most 7200 minutes (5 days)")
    val stage2LeadMinutes: Int? = null,

    @field:Size(max = 1024, message = "Photo URL must be at most 1024 characters")
    val photoUrl: String? = null
) {
    // Те же инварианты места, что при создании (зеркалят CHECK chk_events_location_pair).
    @get:AssertTrue(message = "Latitude and longitude must be provided together")
    val isLocationPairConsistent: Boolean
        get() = (locationLat == null) == (locationLon == null)

    @get:AssertTrue(message = "Either a map point or a location hint is required")
    val isSomeLocationProvided: Boolean
        get() = (locationLat != null && locationLon != null) || !locationHint.isNullOrBlank()
}
