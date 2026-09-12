package com.clubs.bot

import com.clubs.chatlink.SkladchinaChatStatusService
import com.clubs.skladchina.SkladchinaClosedEvent
import com.clubs.skladchina.SkladchinaLockedEvent
import com.clubs.skladchina.SkladchinaProgressChangedEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * «Живой статус сбора»: переводит доменные события сбора в действия [SkladchinaChatStatusService]
 * ПОСЛЕ коммита исходной транзакции. Изменения прогресса только ставят dirty-флаг — реальный
 * edit идёт flush-планировщиком с дебаунсом; закрытие рисуется сразу.
 *
 * Создание сбора здесь НЕ слушается: его оркестрирует [SkladchinaBotNotifier] — чат-пост и
 * DM-рассылка связаны маршрутизатором ([ChatAwareBroadcast]) и обязаны идти последовательно.
 */
@Component
class SkladchinaChatStatusListener(
    private val chatStatusService: SkladchinaChatStatusService
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onProgressChanged(changed: SkladchinaProgressChangedEvent) {
        chatStatusService.markDirty(changed.skladchinaId)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onSkladchinaClosed(closed: SkladchinaClosedEvent) {
        chatStatusService.closeNow(closed.skladchinaId)
    }

    /** Не набрали минимум — сбор отменён без SkladchinaClosedEvent, пост закрывается отсюда. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onLocked(locked: SkladchinaLockedEvent) {
        if (locked.cancelledForShortfall) chatStatusService.closeNow(locked.skladchinaId)
    }
}
