package com.clubs.user

import com.clubs.award.AwardDto
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class MemberProfileDto(
    val userId: UUID,
    val clubId: UUID,
    val firstName: String,
    val username: String?,
    val avatarUrl: String?,
    // Публичные поля профиля, показываются каждому участнику клуба на карточке участника рядом
    // с кольцами репутации по клубу. Уже публичны в профиле и на карточке заявки.
    val bio: String?,
    val interests: List<String>,
    // Member admin S2 — локальные для клуба награды (R3: видны ВСЕМ участникам, в отличие от заметки,
    // доступной только организатору). Только косметика (R4): никогда не выводятся из репутации
    // и не влияют на неё.
    val awards: List<AwardDto>,
    // Роль в membership ("organizer" = владелец клуба). Фронтенд использует это, чтобы отрисовать
    // рамку организатора, когда trust равен null в собственном клубе пользователя.
    val role: String,
    // P1b Trust 0-100. null = "Новичок"/скрыто (ещё нет истории, либо владелец в своём клубе).
    val trust: Int?,
    val promiseFulfillmentPct: BigDecimal?,
    val totalConfirmations: Int?,
    val totalAttendances: Int?,
    // "Возможно → Подтвердил → Пришёл": пришёл, хотя обещал только «возможно». Позитивный сигнал.
    val spontaneityCount: Int?,
    // Влияющая на репутацию история складчин В ЭТОМ клубе: оплачено / (оплачено + просрочено). null,
    // когда блок репутации скрыт; фронтенд прячет кольцо "Сборы" при total == 0.
    val skladchinaPaid: Int?,
    val skladchinaTotal: Int?,
    // Открытые встречи (V62) В ЭТОМ клубе: пришёл / подтверждённых с выясненной явкой. ВНЕ репутации
    // (сырые отметки явки, в ledger не пишутся) — поэтому порог «Новичка» не применяется, но
    // асимметричная видимость (AC-5) та же: null для чужого зрителя, значения — оргу и самому о себе.
    // Фронтенд прячет кольцо при total == 0.
    val openEventsAttended: Int? = null,
    val openEventsTotal: Int? = null,
    // De-Stars, слой 2 — ТОЛЬКО ДЛЯ ОРГАНИЗАТОРА (null для обычных участников): когда заканчивается
    // платное окно доступа этого участника. null также для бесплатных membership (нет истечения).
    // Отображается как «Подписка активна до …».
    val subscriptionExpiresAt: OffsetDateTime? = null,
    // Member admin S1 — ТОЛЬКО ДЛЯ ОРГАНИЗАТОРА (null для обычных участников): приватная заметка организатора.
    val organizerNote: String? = null,
    // De-Stars заявление об оплате взноса — ТОЛЬКО ДЛЯ ОРГАНИЗАТОРА (null для обычных участников):
    // когда участник заявил об оплате, способ ("sbp"|"cash") и URL скриншота (только для sbp).
    // Позволяет организатору проверить подтверждение перед нажатием «Взнос получен».
    val duesClaimedAt: OffsetDateTime? = null,
    val duesClaimMethod: String? = null,
    val duesProofUrl: String? = null,
    // Ответ участника в заявке на вступление (закрытые клубы) — ТОЛЬКО ДЛЯ ОРГАНИЗАТОРА. Null для
    // открытых клубов / без вопроса. Позволяет организатору увидеть «зачем вступил» рядом
    // с подтверждением оплаты на одной карточке.
    val applicationAnswer: String? = null
)
