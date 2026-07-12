package com.clubs.membership

import com.clubs.award.AwardDto
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class MemberListItemDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val role: String,
    val joinedAt: OffsetDateTime?,
    // P1b Trust 0-100. null = «Новичок» (ещё нет истории, либо владелец в своём же клубе — фронтенд
    // использует `role`, чтобы показать организаторскую подачу). Весь блок репутации скрывается при null.
    val trust: Int?,
    val promiseFulfillmentPct: BigDecimal?,
    // Число подтверждений Этапа 2 на текущий момент. Фронтенд показывает строку «Обещания X%» только
    // если это > 0, чтобы участник только-по-финансам (запись складчины, без событий) никогда не
    // показывал вводящий в заблуждение 0% (F5-08).
    val totalConfirmations: Int?,
    // Награды клубного уровня (member admin S2) — публичны для всех участников (R3), показываются
    // чипами на карточке ростера (и в профиле). Косметика; никогда не влияет на репутацию (R4).
    // Пусто, если у участника наград нет.
    val awards: List<AwardDto> = emptyList(),
    // De-Stars Slice 2 — только для дашборда организатора (null для обычных участников): статус
    // доступа ("active"/"frozen") + когда заканчивается оплаченное окно доступа. Управляет
    // корзинами «Скоро закончится» / «Ждут оплаты» / «Активные». `subscriptionExpiresAt` равно null
    // для бесплатных членств.
    val accessStatus: String? = null,
    val subscriptionExpiresAt: OffsetDateTime? = null,
    // Заявка участника об оплате взноса (только для дашборда организатора): когда он заявил об
    // оплате (null = заявки нет) + способ ("sbp"|"cash"). Позволяет списку «Ждут оплаты» пометить
    // «оплата заявлена» ещё до открытия карточки.
    val duesClaimedAt: OffsetDateTime? = null,
    val duesClaimMethod: String? = null
)

/**
 * Участник без доступа в одном из клубов вызывающего: `frozen` (вступил, первый взнос не подтверждён)
 * или `expired` (просрочил продление). Питает кросс-клубовую секцию «Ждут оплаты» на «Мои клубы»,
 * чтобы организатор мог подтверждать взносы, не заходя в каждый клуб. `subscriptionExpiresAt` —
 * истёкшее окно (null при первом вступлении без оплаты); `joinedAt` формирует строку «вступил(а) N назад».
 */
data class OrganizerDuesMember(
    val userId: UUID,
    val firstName: String?,
    val lastName: String?,
    val avatarUrl: String?,
    val telegramUsername: String?,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val joinedAt: OffsetDateTime,
    val subscriptionExpiresAt: OffsetDateTime?,
    val duesClaimedAt: OffsetDateTime?,
    val duesClaimMethod: String?,
    // Статус членства ("frozen"|"expired") — фронтенд различает «первый взнос» и «просрочку продления»
    // (у второй в карточке нет «Отказать · вернуть», а мета говорит о подписке, не о вступлении).
    val accessStatus: String
)

data class OrganizerDuesMemberDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val telegramUsername: String?,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val joinedAt: OffsetDateTime?,
    val subscriptionExpiresAt: OffsetDateTime?,
    // Кросс-клубовая «Ждут оплаты»: заявка участника об оплате (null = нет заявки) + способ, чтобы
    // строка могла пометить «оплата заявлена» и организатор приоритизировал подтверждение.
    val duesClaimedAt: OffsetDateTime?,
    val duesClaimMethod: String?,
    // "frozen" (первый взнос) | "expired" (просрочка продления) — управляет текстами и действиями карточки.
    val accessStatus: String
)

/** Заявка об оплате взноса по инициативе участника (de-Stars): frozen-участник заявляет, что оплатил.
 *  method = "sbp"|"cash"; proofUrl — ссылка на загруженный скриншот, обязательна для "sbp", игнорируется
 *  для "cash". */
data class ClaimDuesRequest(
    @field:NotBlank(message = "Укажите способ оплаты")
    val method: String,
    @field:Size(max = 1000, message = "Некорректная ссылка на скриншот")
    val proofUrl: String? = null
)

/** De-Stars B+C — организатор отказывает в оплаченном вступлении (возврат оффлайн). Причина
 *  опциональна, показывается участнику. */
data class RejectDuesRequest(
    @field:Size(max = 500, message = "Причина: максимум 500 символов")
    val reason: String? = null
)

/** Организатор исключает участника из клуба. Причина обязательна (показывается исключённому в DM). */
data class RemoveMemberRequest(
    @field:NotBlank(message = "Укажите причину удаления")
    @field:Size(min = 5, max = 500, message = "Причина: от 5 до 500 символов")
    val reason: String
)

/** Member admin S1 — установить приватную заметку организатора (null/пусто очищает её). */
data class UpdateNoteRequest(
    @field:Size(max = 500, message = "Заметка: максимум 500 символов")
    val note: String?
)

/** Member admin S1 — установить кастомную дату окончания окна доступа («своя дата»). Должна быть в будущем. */
data class SetAccessUntilRequest(
    @field:NotNull(message = "Укажите дату")
    val until: OffsetDateTime
)

/** Co-organizers — смена роли участника владельцем: "member" | "co_organizer" ("organizer" запрещён —
 *  передача владения вне скоупа, club-leave PR-2). Значение валидирует MemberRoleService (400). */
data class UpdateMemberRoleRequest(
    @field:NotBlank(message = "Укажите роль")
    val role: String
)
