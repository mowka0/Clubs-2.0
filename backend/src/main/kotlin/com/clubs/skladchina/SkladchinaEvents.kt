package com.clubs.skladchina

import com.clubs.generated.jooq.enums.SkladchinaStatus
import java.util.UUID

/**
 * Публикуется после создания складчины и коммита транзакции.
 * Слушатель (SkladchinaBotNotifier) отправляет участникам личные сообщения.
 * Канонический паттерн TransactionalEventListener см. в SkladchinaBotNotifier.
 */
data class SkladchinaCreatedEvent(
    val skladchinaId: UUID,
    val clubId: UUID,
    val clubName: String,
    val title: String,
    val description: String?,
    val paymentLink: String,
    val paymentMode: String,
    val totalGoalKopecks: Long?,
    val deadline: java.time.OffsetDateTime,
    val affectsReputation: Boolean,
    val participantUserIds: List<UUID>
)

/**
 * Публикуется после любого изменения ПУБЛИЧНОГО прогресса складчины (оплата/отказ/орг-отметка/
 * орг-снятие/одобрение отказа/сдвиг дедлайна заявкой на отказ) и коммита транзакции.
 * Слушатель (SkladchinaChatStatusListener) ставит dirty-флаг «живого статуса сбора» —
 * реальная перерисовка идёт flush-планировщиком с дебаунсом.
 */
data class SkladchinaProgressChangedEvent(
    val skladchinaId: UUID
)

/**
 * Публикуется после того, как участник открывает запрос на отказ (шаблоны REQUIRES_APPROVAL, V28) и
 * транзакция закоммичена. Слушатель отправляет организатору личное сообщение с заявителем + причиной и
 * кнопкой на страницу складчины.
 */
data class SkladchinaDeclineRequestedEvent(
    val skladchinaId: UUID,
    val creatorId: UUID,
    val requesterUserId: UUID,
    val clubName: String,
    val title: String,
    val reason: String
)

/**
 * Публикуется после того, как организатор ОТКЛОНЯЕТ запрос на отказ (V29) и транзакция закоммичена.
 * Слушатель отправляет отклонённому участнику личное сообщение с причиной организатора и кнопкой на
 * страницу складчины (участник всё равно должен заплатить).
 */
data class SkladchinaDeclineRejectedEvent(
    val skladchinaId: UUID,
    val participantUserId: UUID,
    val clubName: String,
    val title: String,
    val reason: String
)

/**
 * Публикуется после закрытия складчины (вручную, цель достигнута, все ответили или
 * авто-закрытие шедулером) и коммита транзакции. Слушатель уведомляет
 * создателя итоговой сводкой.
 */
data class SkladchinaClosedEvent(
    val skladchinaId: UUID,
    val creatorId: UUID,
    val clubName: String,
    val title: String,
    val finalStatus: SkladchinaStatus,
    val collectedKopecks: Long,
    val totalGoalKopecks: Long?,
    val paidCount: Int,
    val participantCount: Int,
    val affectsReputation: Boolean,
    /**
     * Участники, промолчавшие до дедлайна и только что получившие запись -40
     * в леджере. Непусто ТОЛЬКО для влияющего на репутацию закрытия в момент дедлайна
     * или после — уведомитель шлёт каждому из них личное сообщение о штрафе (блокер запуска,
     * уведомление #3 редизайна).
     */
    val expiredParticipantUserIds: List<UUID> = emptyList()
)
