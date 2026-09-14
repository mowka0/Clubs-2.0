package com.clubs.skladchina

import com.clubs.debt.DebtDto
import com.clubs.debt.DebtPersonDto
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Создание сбора любого вида (docs/modules/skladchina-v3.md § 7, § 9). Какие поля обязательны,
 * решает вид — проверяет сервис создания, а не аннотации: у voluntary срок и сумма необязательны,
 * у shared сумма обязательна, у per_head это цена за человека.
 */
data class CreateSkladchinaRequest(
    @field:NotBlank @field:Size(max = 255)
    val title: String,

    @field:Size(max = 2000)
    val description: String? = null,
    @field:Size(max = 2000)
    val rules: String? = null,
    val photoUrl: String? = null,

    @field:NotBlank
    val kind: String,                              // shared | per_head | voluntary

    @field:Positive
    val amountKopecks: Long? = null,

    @field:NotBlank @field:Size(max = 1000)
    val paymentLink: String,
    @field:Size(max = 500)
    val paymentMethodNote: String? = null,

    val deadline: OffsetDateTime? = null,

    // shared после встречи: список = пришедшие на встречу.
    val eventId: UUID? = null,
    // shared до события: этап «Кто в деле?» до этого момента; минимум людей, иначе сбор отменится сам.
    val enrollmentUntil: OffsetDateTime? = null,
    @field:Positive
    val minParticipants: Int? = null,
    // Этап «Кто в деле?»: отмечать ли создателя в деле сразу (чекбокс формы, по умолчанию да).
    val enrollCreator: Boolean = true,
    // per_head: создатель берёт и себе — его долг ложится сразу received; кнопки «Беру» у него нет.
    val takeCreator: Boolean = true,
    @field:Min(1) @field:Max(MAX_QUANTITY.toLong())
    val creatorQuantity: Int = 1,

    // voluntary: от кого скрыть (тихий сбор).
    val hiddenFromUserId: UUID? = null,
    // voluntary: создатель тоже скидывается — его взнос ложится сразу received (null = не скидывается).
    @field:Positive
    val creatorContributionKopecks: Long? = null,
    // voluntary «каждый сколько считает нужным»: кого позвали скинуться (DM только им, на экране
    // «Скидываются»); пусто = зовём всех участников клуба. Можно вместе с eventId.
    val invitedUserIds: List<UUID> = emptyList(),

    // shared без встречи и без этапа: список должников. Суммы либо у всех (по людям), либо ни у кого (поровну).
    @field:Valid
    val debtors: List<DebtorRequest> = emptyList()
)

data class DebtorRequest(
    @field:NotNull
    val userId: UUID,
    @field:Positive
    val amountKopecks: Long? = null
)

/** «В деле» / «Беру»: заметка необязательна (размер футболки и т.п.). */
data class JoinSkladchinaRequest(
    @field:Size(max = 200)
    val note: String? = null,
    // per_head: сколько штук берёт (по умолчанию 1); у этапа записи игнорируется.
    @field:Min(1) @field:Max(MAX_QUANTITY.toLong())
    val quantity: Int = 1
)

// Верхняя граница «штук на человека» в per_head: защита от опечатки, не бизнес-ограничение.
const val MAX_QUANTITY = 50

/** «Заказываю» (per_head): брать ли в долг тех, кто нажал «Оплачу позже» (иначе они выбывают, обещание аннулируется). */
data class OrderSkladchinaRequest(
    val includePromised: Boolean = false
)

/** «Перевёл N ₽» (voluntary). */
data class ContributeRequest(
    @field:NotNull @field:Positive
    val amountKopecks: Long
)

/** Добавить человека в shared-сбор; сумма по умолчанию = доля последнего добавленного. */
data class AddDebtorRequest(
    @field:NotNull
    val userId: UUID,
    @field:Positive
    val amountKopecks: Long? = null
)

/** Заменить должника другим человеком с той же суммой. */
data class ReplaceDebtorRequest(
    @field:NotNull
    val userId: UUID
)

data class SkladchinaDetailDto(
    val id: UUID,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    val creatorId: UUID,
    // Кто собирает: панель «Кому переводить» у плательщика и крошка «собирает …».
    val creator: DebtPersonDto,

    val title: String,
    val description: String?,
    val rules: String?,
    val photoUrl: String?,

    val kind: String,
    val amountKopecks: Long?,
    // Знаменатель «получено X из Y»: сумма живых долгов, а до их появления — amountKopecks.
    val targetKopecks: Long?,
    val receivedKopecks: Long,
    val claimedKopecks: Long,
    val paymentLink: String,
    val paymentMethodNote: String?,

    val deadline: OffsetDateTime?,
    val enrollmentUntil: OffsetDateTime?,
    val minParticipants: Int?,
    val lockedAt: OffsetDateTime?,
    val orderedAt: OffsetDateTime?,

    val eventId: UUID?,
    val eventTitle: String?,
    val eventDatetime: OffsetDateTime?,

    val status: String,
    val closedAt: OffsetDateTime?,

    val isCreator: Boolean,
    // Отменить может создатель или владелец клуба (PO 2026-09-12; со-организаторы нет).
    val canCancel: Boolean,

    val isEnrolling: Boolean,
    val enrolledCount: Int,
    val myEnrolled: Boolean,

    val debtCount: Int,
    val receivedCount: Int,
    val openCount: Int,
    val claimedCount: Int,
    // per_head: штук оплачено (сумма quantity по received) — «куплено N».
    val receivedItems: Int,

    // Люди на экране сбора: на этапе «Кто в деле?» — отметившиеся «В деле» (видно всем участникам);
    // в «По желанию» — кого позвали скинуться (`people` формы, блок «Скидываются»); иначе пустой список.
    val enrolled: List<DebtPersonDto>,

    // Мой долг как должника (null = у меня долга в этом сборе нет).
    val myDebt: DebtDto?,
    // Список долгов сбора — ТОЛЬКО создателю; остальным null.
    val debts: List<DebtDto>?
)

/** Строка списка «по какой встрече скидываемся»: только события, которые примет создание. */
data class SplittableEventDto(
    val eventId: UUID,
    val title: String,
    val eventDatetime: OffsetDateTime,
    val attendedCount: Int,
    val attendedUserIds: List<UUID>
)

// Состояние сбора, привязанного к встрече — управляет кнопкой «Скинуться» на EventPage.
// Оба null = сбора ещё нет (кнопка создаёт). active → открыть его; collected → уже собрано.
data class EventSplitStateDto(
    val skladchinaId: UUID?,
    val status: String?
)

data class MySkladchinaListItemDto(
    val id: UUID,
    val title: String,
    val clubId: UUID,
    val clubName: String,
    val clubAvatarUrl: String?,
    // «собирает …» в карточке ленты у чужого сбора.
    val creatorName: String,
    val kind: String,
    val amountKopecks: Long?,
    val targetKopecks: Long?,
    val receivedKopecks: Long,
    val debtCount: Int,
    val receivedCount: Int,
    val deadline: OffsetDateTime?,
    val status: String,
    val isCreator: Boolean,
    val myDebtStatus: String?,
    // Мне нужно действовать: открытый долг как должнику или «Отдал» ждёт моего ответа как получателя.
    val actionRequired: Boolean,
    val photoUrl: String?
)

/** Число сборов, где от пользователя ждут действия (бейдж таба). */
data class ActionRequiredCountDto(val count: Int)
