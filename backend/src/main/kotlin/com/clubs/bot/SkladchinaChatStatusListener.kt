package com.clubs.bot

import com.clubs.chatlink.SkladchinaChatStatusService
import com.clubs.skladchina.SkladchinaClosedEvent
import com.clubs.skladchina.SkladchinaCreatedEvent
import com.clubs.skladchina.SkladchinaProgressChangedEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * «Живой статус сбора» (club-chat-link слайс 3.5): переводит доменные события жизненного цикла
 * складчины в действия [SkladchinaChatStatusService] ПОСЛЕ коммита исходной транзакции
 * (AFTER_COMMIT — как остальные бот-листенеры). Изменения прогресса только ставят dirty-флаг —
 * реальный edit идёт flush-планировщиком с дебаунсом.
 *
 * Создание складчины здесь НЕ слушается: его оркестрирует [SkladchinaBotNotifier] —
 * чат-пост и DM-рассылка связаны маршрутизатором ([ChatAwareBroadcast]) и обязаны идти
 * последовательно, а не гоняться двумя листенерами.
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
        chatStatusService.onSkladchinaClosed(closed)
    }
}
