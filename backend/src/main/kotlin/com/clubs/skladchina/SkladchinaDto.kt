package com.clubs.skladchina

import jakarta.validation.Valid
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class CreateSkladchinaRequest(
    @field:NotBlank @field:Size(max = 255)
    val title: String,

    val description: String? = null,
    val rules: String? = null,
    val photoUrl: String? = null,

    // Выбирает стратегию шаблона. По умолчанию "custom" = поведение Фазы A.
    val template: String = "custom",               // "custom" | "split_bill" | "gear" | "booking" | "birthday"
    // split_bill: исходное событие, чей счёт делится. Игнорируется другими шаблонами.
    val eventId: UUID? = null,
    // split_bill: исключить организатора из числа участников, с которых берут деньги (он не должен долю).
    // Режим "поровну" затем делит счёт между оставшимися участниками. Игнорируется другими шаблонами.
    val excludeSelf: Boolean = false,

    @field:NotNull
    val paymentMode: String,                       // "fixed_equal" | "fixed_individual" | "voluntary"

    @field:Positive
    val totalGoalKopecks: Long? = null,            // цель для fixed_equal; для split_bill: сумма счёта

    @field:NotBlank @field:Size(max = 1000)
    val paymentLink: String,

    val paymentMethodNote: String? = null,

    @field:NotNull @field:Future
    val deadline: OffsetDateTime,

    val affectsReputation: Boolean = false,

    // Обязательно для режимов custom/fixed; игнорируется в split_bill (участники берутся из посещаемости).
    // Валидация по конкретному шаблону живёт в стратегии, а не в DTO.
    @field:Valid
    val participants: List<CreateSkladchinaParticipantRequest> = emptyList()
)

data class CreateSkladchinaParticipantRequest(
    @field:NotNull
    val userId: UUID,
    @field:Positive
    val expectedAmountKopecks: Long? = null        // обязателен для fixed_individual
)

data class MarkPaidRequest(
    // Nullable начиная с Фазы A (A-1): в fixed-режимах сервер сам записывает назначенную
    // долю, и клиент ничего не присылает. Обязателен только для voluntary (валидируется
    // в сервисе, по режиму). @Positive применяется только когда значение присутствует.
    @field:Positive
    val declaredAmountKopecks: Long? = null
)

// V28: запрос участника на отказ (шаблоны REQUIRES_APPROVAL) — причина обязательна.
data class RequestDeclineRequest(
    @field:NotBlank @field:Size(max = 500)
    val reason: String
)

// V28/V29: организатор разрешает запрос на отказ. Отклонение (approve=false) требует причины
// (почему участник всё же должен заплатить) — валидируется в сервисе, т.к. условно от approve.
data class ResolveDeclineRequest(
    @field:NotNull
    val approve: Boolean,
    @field:Size(max = 500)
    val rejectReason: String? = null
)

data class SkladchinaDetailDto(
    val id: UUID,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val creatorId: UUID,

    val title: String,
    val description: String?,
    val rules: String?,
    val photoUrl: String?,

    val template: String,                          // custom | split_bill | gear | booking | birthday
    val eventId: UUID?,                            // split_bill: исходное событие (иначе null)
    val paymentMode: String,
    val totalGoalKopecks: Long?,
    val collectedKopecks: Long,
    val paymentLink: String,
    val paymentMethodNote: String?,

    val deadline: OffsetDateTime,
    val affectsReputation: Boolean,
    val status: String,
    val closedAt: OffsetDateTime?,

    val isOrganizerView: Boolean,                  // вызывающий == создатель
    val myStatus: String?,                         // pending|paid|declined|expired_no_response|released или null
    val myExpectedAmountKopecks: Long?,
    val myDeclaredAmountKopecks: Long?,

    // V28 отказ-с-подтверждением
    val declineRequiresApproval: Boolean,          // политика шаблона — фронтенд использует флоу запроса
    val myDeclineRequested: Boolean,               // у вызывающего открытый запрос на отказ, ждёт организатора
    val myDeclineRejected: Boolean,                // отказ вызывающего отклонён — должен заплатить
    val myDeclineRejectNote: String?,              // V29: причина организатора для отклонения отказа

    val participants: List<SkladchinaParticipantDto>?,   // не-null ТОЛЬКО для организатора
    val participantCount: Int,
    val paidCount: Int,
    val pendingCount: Int                          // #3: видно всем, чтобы последний pending видел, что осталось
)

// Состояние сплита, привязанного к событию — управляет кнопкой "Разделить счёт" на EventPage.
// Оба null = сплита ещё нет (кнопка создаёт). status active → открыть его; closed_success → уже собрано.
data class EventSplitStateDto(
    val skladchinaId: UUID?,
    val status: String?
)

data class SkladchinaParticipantDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val expectedAmountKopecks: Long?,
    val declaredAmountKopecks: Long?,
    val status: String,
    val paidAt: OffsetDateTime?,
    // V28: открытый запрос на отказ (вид организатора) — показать заметку + кнопки approve/reject.
    val declineRequested: Boolean,
    val declineNote: String?,
    val declineRejected: Boolean,
    val declineRejectNote: String?                 // V29: причина организатора, если отказ отклонён
)

data class MySkladchinaListItemDto(
    val id: UUID,
    val title: String,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val template: String,
    val paymentMode: String,
    val totalGoalKopecks: Long?,
    val collectedKopecks: Long,
    val participantCount: Int,
    val paidCount: Int,
    val deadline: OffsetDateTime,
    val status: String,
    val isOrganizerView: Boolean,
    val myStatus: String?,
    val actionRequired: Boolean,
    val affectsReputation: Boolean
)

data class SkladchinaUploadResponse(
    val photoUrl: String
)
