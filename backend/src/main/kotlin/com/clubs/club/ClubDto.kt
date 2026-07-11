package com.clubs.club

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

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
    val city: String,
    val subscriptionPrice: Int,
    val memberCount: Int,
    val memberLimit: Int,
    val avatarUrl: String?,
    val nearestEvent: NearestEventDto?,
    val tags: List<String> = emptyList()
)

data class ClubDetailDto(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val description: String,
    val category: String,
    val accessType: String,
    val city: String,
    val district: String?,
    val memberLimit: Int,
    val subscriptionPrice: Int,
    val avatarUrl: String?,
    val rules: String?,
    val applicationQuestion: String?,
    val inviteLink: String?,
    val memberCount: Int,
    val isActive: Boolean,
    // Реквизиты для взносов по СБП — заполняются только для участников клуба (active/frozen) + владельца; иначе null.
    val paymentLink: String?,
    val paymentMethodNote: String?,
    // Чат-интеграция (club-chat-link): к клубу привязан телеграм-чат и бот в нём жив.
    // Публично — гость видит чип «у клуба есть чат» (мокап 02-C).
    val chatLinked: Boolean = false,
    // Включён «вход в чат через заявки» (дверь). Тоже публично — чип обещает вход после одобрения.
    val chatDoorEnabled: Boolean = false,
    // Door-ссылка для кнопки «Чат клуба» — ТОЛЬКО участникам с доступом (active / cancelled-в-периоде)
    // и владельцу; гостям/frozen/expired — null (least exposure, как paymentLink).
    val chatInviteLink: String? = null,
    // Имя владельца — только для посадочной инвайта (подпись «Приглашение от <имя>», club-invites).
    // В остальных ответах null: не тянем лишний lookup пользователя.
    val ownerFirstName: String? = null,
    val ownerLastName: String? = null
)

/**
 * Ответ POST /api/clubs/{id}/invite-share (club-invites): deep-link для «Скопировать ссылку»
 * + id prepared message для нативного шаринга. preparedMessageId = null — Telegram не ответил,
 * фронт оставляет в шите только копирование.
 */
data class InviteShareDto(
    val inviteUrl: String,
    val preparedMessageId: String?
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

    @field:NotBlank(message = "City is required")
    val city: String,

    val district: String? = null,

    @field:NotNull(message = "Member limit is required")
    // Минимум временно 1 (было 10) — тест заполняемости полного клуба (PO 2026-07-11, club-invites).
    @field:Min(value = 1, message = "Member limit must be at least 1")
    @field:Max(value = 80, message = "Member limit must be at most 80")
    val memberLimit: Int,

    @field:NotNull(message = "Subscription price is required")
    @field:Min(value = 0, message = "Subscription price must be non-negative")
    val subscriptionPrice: Int,

    val avatarUrl: String? = null,
    val rules: String? = null,
    val applicationQuestion: String? = null,

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

    val city: String? = null,
    val district: String? = null,

    // Минимум временно 1 (было 10) — тест заполняемости полного клуба (PO 2026-07-11, club-invites).
    @field:Min(value = 1, message = "Member limit must be at least 1")
    @field:Max(value = 80, message = "Member limit must be at most 80")
    val memberLimit: Int? = null,

    @field:Min(value = 0, message = "Subscription price must be non-negative")
    val subscriptionPrice: Int? = null,

    val avatarUrl: String? = null,
    val rules: String? = null,
    val applicationQuestion: String? = null,

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
    val city: String? = null,
    val accessType: String? = null,
    val minPrice: Int? = null,
    val maxPrice: Int? = null,
    val search: String? = null,
    val page: Int = 0,
    val size: Int = 20
)
