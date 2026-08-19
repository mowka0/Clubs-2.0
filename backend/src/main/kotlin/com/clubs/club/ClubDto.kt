package com.clubs.club

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

// Потолок размера списка тем В ЗАПРОСЕ — защита от абсурдного тела, а не бизнес-лимит.
// Бизнес-лимит (7) применяет InterestService, отбрасывая лишние молча: восьмая тема не повод
// уронить создание клуба, а десять тысяч строк в JSON — повод отказать сразу.
private const val MAX_INTERESTS_IN_REQUEST = 50

data class NearestEventDto(
    val id: UUID,
    val title: String,
    val eventDatetime: OffsetDateTime,
    val goingCount: Int
)

data class ClubListItemDto(
    val id: UUID,
    val name: String,
    val category: String,
    val accessType: String,
    // `city` — денормализованное имя из справочника, только для показа. Источник правды — cityId;
    // сервер пишет оба поля вместе, поэтому разъехаться они не могут.
    val city: String,
    // null = легаси-клуб, город которого не распознался при миграции V74.
    val cityId: UUID? = null,
    val subscriptionPrice: Int,
    val memberCount: Int,
    val memberLimit: Int,
    val avatarUrl: String?,
    // Обложка карточки каталога: с V70 у клуба своя картинка обложки, аватар остаётся кружком.
    // Фолбэк на avatarUrl держит фронтенд — у клубов, созданных до разделения, обложки нет.
    val coverUrl: String? = null,
    val nearestEvent: NearestEventDto?,
    // ВНИМАНИЕ: `tags` — вычисляемые бейджи каталога («Новый», «Популярный», «Свободные места»),
    // считаются на лету в findAll. Темы клуба из словаря — это `interests` ниже.
    val tags: List<String> = emptyList(),
    // Темы клуба (0–7) — уточнение категории, по ним же работает поиск (club-interests.md).
    val interests: List<String> = emptyList()
)

data class ClubDetailDto(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val description: String,
    val category: String,
    val accessType: String,
    val city: String,
    // null = легаси-клуб с нераспознанным городом; организатор уточняет город из управления.
    val cityId: UUID? = null,
    val district: String?,
    val memberLimit: Int,
    val subscriptionPrice: Int,
    val avatarUrl: String?,
    // Обложка шапки страницы клуба (V70). NULL = фронтенд рисует градиент по категории.
    val coverUrl: String? = null,
    val rules: String?,
    val applicationQuestion: String?,
    val inviteLink: String?,
    val memberCount: Int,
    val isActive: Boolean,
    // Реквизиты для взносов по СБП — заполняются только для участников клуба (active/frozen) + владельца; иначе null.
    val paymentLink: String?,
    val paymentMethodNote: String?,
    /**
     * Мастер наполнения пройден (V82). false — на странице клуба висит баннер «Клуб ещё не
     * заполнен»: клуб родился из чата, и владелец до конца мастера не дошёл. Отдаём флагом, а
     * не датой: фронту важен только сам факт.
     */
    val setupCompleted: Boolean = true,
    // Чат-интеграция (club-chat-link): к клубу привязан телеграм-чат и бот в нём жив.
    // Публично — гость видит чип «у клуба есть чат» (мокап 02-C).
    val chatLinked: Boolean = false,
    // Включён «вход в чат через заявки» (дверь). Тоже публично — чип обещает вход после одобрения.
    val chatDoorEnabled: Boolean = false,
    // Door-ссылка для кнопки «Чат клуба» — ТОЛЬКО участникам с доступом (active / cancelled-в-периоде)
    // и владельцу; гостям/frozen/expired — null (least exposure, как paymentLink).
    val chatInviteLink: String? = null,
    /**
     * Посадочная приглашения: по ЭТОЙ ссылке нужна заявка, а не прямое вступление (V71 — приглашение
     * из Telegram в клуб «по заявке»). Заполняется ТОЛЬКО в ответе `GET /api/invite/{code}`;
     * на остальных путях всегда false.
     */
    val inviteRequiresApplication: Boolean = false,
    // Имя владельца — только для посадочной инвайта (подпись «Приглашение от <имя>», club-invites).
    // В остальных ответах null: не тянем лишний lookup пользователя.
    val ownerFirstName: String? = null,
    val ownerLastName: String? = null,
    // Темы клуба (0–7) из общего с профилем словаря — уточняют категорию (club-interests.md).
    val interests: List<String> = emptyList()
)

/**
 * Ответ POST /api/clubs/{id}/invite-share (club-invites): deep-link для «Скопировать ссылку»
 * + id prepared message для нативного шаринга. preparedMessageId = null — Telegram не ответил,
 * фронт оставляет в шите только копирование.
 */
data class InviteShareDto(
    /**
     * Ссылка для «Скопировать ссылку». У менеджера это ПРЯМОЙ код (вход мимо заявки), у обычного
     * участника — заявочный: раздать ссылку в обход одобрения он не может (решение PO 2026-07-30).
     */
    val inviteUrl: String,
    val preparedMessageId: String?,
    /**
     * Вход по [inviteUrl] проходит МИМО одобрения организатора — true только у менеджера клуба
     * «по заявке». Фронтенд подписывает этим кнопку копирования; в open/private одобрения не
     * существует, поэтому там false и подписывать нечего.
     */
    val linkBypassesApproval: Boolean = false
)

data class CreateClubRequest(
    @field:NotBlank(message = "Name is required")
    @field:Size(max = 60, message = "Name must be at most 60 characters")
    val name: String,

    @field:NotBlank(message = "Description is required")
    @field:Size(max = 500, message = "Description must be at most 500 characters")
    val description: String,

    @field:NotBlank(message = "Category is required")
    val category: String,

    @field:NotBlank(message = "Access type is required")
    val accessType: String,

    // Город только из справочника: свободного текста в контракте больше нет, поэтому клуб-призрак
    // с городом «мск» создать физически нельзя. Существование проверяет ClubService.
    @field:NotNull(message = "City is required")
    val cityId: UUID,

    val district: String? = null,

    @field:NotNull(message = "Member limit is required")
    // Минимум временно 1 (было 10) — тест заполняемости полного клуба (PO 2026-07-11, club-invites).
    @field:Min(value = 1, message = "Member limit must be at least 1")
    // Потолок 500 (V81, было 80): клуб создаётся из телеграм-чата и вмещает всех его участников.
    @field:Max(value = 500, message = "Member limit must be at most 500")
    val memberLimit: Int,

    @field:NotNull(message = "Subscription price is required")
    @field:Min(value = 0, message = "Subscription price must be non-negative")
    val subscriptionPrice: Int,

    val avatarUrl: String? = null,
    val rules: String? = null,
    val applicationQuestion: String? = null,

    /**
     * Темы клуба. Лишние сверх [InterestNormalizer.MAX_CLUB_COUNT] отбрасываются молча —
     * потолок здесь только против абсурдного тела запроса, а не против восьмой темы.
     */
    @field:Size(max = MAX_INTERESTS_IN_REQUEST, message = "Слишком много тем")
    val interests: List<String>? = null,

    // Реквизиты для взносов по СБП. Обязательны при subscriptionPrice > 0 (проверяется в ClubService.createClub):
    // платный клуб обязан сообщить участникам, как платить. paymentLink = ссылка СБП/телефон; note = опциональная подсказка.
    @field:Size(max = 500, message = "Реквизиты: максимум 500 символов")
    val paymentLink: String? = null,
    @field:Size(max = 200, message = "Подсказка: максимум 200 символов")
    val paymentMethodNote: String? = null
)

data class UpdateClubRequest(
    @field:Size(max = 60, message = "Name must be at most 60 characters")
    val name: String? = null,

    @field:Size(max = 500, message = "Description must be at most 500 characters")
    val description: String? = null,

    val cityId: UUID? = null,
    val district: String? = null,

    // Минимум временно 1 (было 10) — тест заполняемости полного клуба (PO 2026-07-11, club-invites).
    @field:Min(value = 1, message = "Member limit must be at least 1")
    // Потолок 500 (V81, было 80): клуб создаётся из телеграм-чата и вмещает всех его участников.
    @field:Max(value = 500, message = "Member limit must be at most 500")
    val memberLimit: Int? = null,

    @field:Min(value = 0, message = "Subscription price must be non-negative")
    val subscriptionPrice: Int? = null,

    val avatarUrl: String? = null,
    // Обложка шапки страницы клуба (V70) — та же конвенция nullable-поля: null = оставить как есть,
    // пустая строка = очистить в NULL (тогда рисуется градиент по категории).
    val coverUrl: String? = null,
    val rules: String? = null,
    val applicationQuestion: String? = null,

    /**
     * Мастер наполнения пройден: true ставит отметку (повторная не сдвигает дату), null — не
     * трогать. Снять отметку нельзя: «клуб заполнен» — событие, а не тумблер.
     */
    val setupCompleted: Boolean? = null,

    /**
     * Темы клуба. Та же конвенция, что у остальных полей: null = не трогать, пустой список =
     * снять все темы, непустой = заменить набор целиком.
     */
    @field:Size(max = MAX_INTERESTS_IN_REQUEST, message = "Слишком много тем")
    val interests: List<String>? = null,

    // Реквизиты для взносов по СБП (настройки). null = оставить как есть; пустая строка = очистить в NULL (как rules/district).
    @field:Size(max = 500, message = "Реквизиты: максимум 500 символов")
    val paymentLink: String? = null,
    @field:Size(max = 200, message = "Подсказка: максимум 200 символов")
    val paymentMethodNote: String? = null
)

/**
 * Карточка доверия для экрана оплаты взноса — кому участник собирается перевести деньги (de-Stars: деньги
 * идут напрямую организатору, вне платформы). Фокус на аккаунте; фронтенд скрывает факты, которые ещё не
 * значимы (clubsCount < 2, trustedMembers ниже порога), чтобы у свежего аккаунта никогда не показывались нули.
 */
data class OrganizerCardDto(
    val firstName: String,
    val lastName: String?,
    val username: String?,
    val avatarUrl: String?,
    // Возраст аккаунта организатора на НАШЕЙ платформе (users.created_at). Показывается всегда ("с {дата}" / "недавно").
    val onPlatformSince: OffsetDateTime,
    // Активные клубы, которыми владеет организатор (показывается только при ≥ 2).
    val clubsCount: Int,
    // Активные участники (не организаторы) во всех активных клубах организатора — «доверяют N участников»
    // (показывается только выше порога). Frozen (ещё не оплатившие) участники исключены — они не доказательство.
    val trustedMembers: Int
)

data class ClubFilterParams(
    val category: String? = null,
    // Фильтр по FK, а не по строке: раньше сравнение шло equalIgnoreCase, и клуб «мск» не находился
    // фильтром «Москва» никогда. Регистр, пробелы и написание больше не влияют ни на что.
    val cityId: UUID? = null,
    val accessType: String? = null,
    val minPrice: Int? = null,
    val maxPrice: Int? = null,
    val search: String? = null,
    val page: Int = 0,
    val size: Int = 20
)
