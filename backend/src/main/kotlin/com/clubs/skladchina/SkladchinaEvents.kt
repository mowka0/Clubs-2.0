package com.clubs.skladchina

import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.generated.jooq.enums.SkladchinaStatus
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Доменные события сбора. Публикуются внутри транзакции, слушатели в `bot` работают ПОСЛЕ коммита
 * (`@TransactionalEventListener`, см. SkladchinaBotNotifier / SkladchinaChatStatusListener).
 */

/**
 * Сбор создан. [recipientUserIds] — кому рассказать: у shared с долгами это должники, у сбора с
 * этапом записи, per_head и voluntary — все активные участники клуба (кроме скрытого).
 * [debtorShares] непусто, только если долги созданы сразу (shared без этапа): DM называет долю.
 */
data class SkladchinaCreatedEvent(
    val skladchinaId: UUID,
    val clubId: UUID,
    val clubName: String,
    val creatorId: UUID,
    val kind: SkladchinaKind,
    val title: String,
    val description: String?,
    val paymentLink: String,
    val paymentMethodNote: String?,
    val amountKopecks: Long?,
    val deadline: OffsetDateTime?,
    val enrollmentUntil: OffsetDateTime?,
    val hiddenFromUserId: UUID?,
    val recipientUserIds: List<UUID>,
    val debtorShares: Map<UUID, Long>
)

/**
 * Любое изменение публичного прогресса сбора (долг сменил состояние, кто-то отметился «В деле»,
 * список заморожен, заказ сделан). Слушатель ставит dirty-флаг чат-поста; перерисовка идёт с дебаунсом.
 */
data class SkladchinaProgressChangedEvent(
    val skladchinaId: UUID
)

/**
 * Этап «Кто в деле?» закрыт (по сроку шедулером или рукой создателя). Либо долги созданы
 * ([debtorShares] непусто, [cancelledForShortfall] = false), либо не набрали минимум —
 * сбор отменён, долгов нет, денег никто не переводил.
 */
data class SkladchinaLockedEvent(
    val skladchinaId: UUID,
    val clubName: String,
    val creatorId: UUID,
    val title: String,
    val paymentLink: String,
    val paymentMethodNote: String?,
    val deadline: OffsetDateTime?,
    val enrolledCount: Int,
    val minParticipants: Int?,
    val cancelledForShortfall: Boolean,
    val debtorShares: Map<UUID, Long>
)

/** per_head: «Заказываю» нажато — [droppedUserIds] выбыли без долга. */
data class SkladchinaOrderedEvent(
    val skladchinaId: UUID,
    val clubName: String,
    val title: String,
    val droppedUserIds: List<UUID>
)

/**
 * Сбор закрыт (collected сам или рукой, cancelled рукой). [refundUserIds] — у отменённого сбора:
 * кому создателю вернуть уже полученные деньги.
 */
data class SkladchinaClosedEvent(
    val skladchinaId: UUID,
    val creatorId: UUID,
    val clubName: String,
    val title: String,
    val kind: SkladchinaKind,
    val finalStatus: SkladchinaStatus,
    val receivedKopecks: Long,
    val targetKopecks: Long?,
    val receivedCount: Int,
    val debtCount: Int,
    val refunds: Map<UUID, Long> = emptyMap()
)
