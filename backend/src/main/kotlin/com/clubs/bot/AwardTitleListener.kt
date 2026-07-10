package com.clubs.bot

import com.clubs.award.AwardGrantedEvent
import com.clubs.award.AwardRevokedEvent
import com.clubs.chatlink.TitleService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Титулы наград (club-chat-link, слайс 4): пересчёт титула в чате на каждое изменение набора
 * наград участника — ПОСЛЕ коммита исходной транзакции (AFTER_COMMIT, как ChatDoorListener:
 * откат выдачи награды не должен оставить титул). Действие — @Async в [TitleService], best-effort.
 */
@Component
class AwardTitleListener(
    private val titleService: TitleService
) {
    private val log = LoggerFactory.getLogger(AwardTitleListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onAwardGranted(event: AwardGrantedEvent) {
        log.info("Award title: award granted, recomputing: clubId={} userId={}", event.clubId, event.userId)
        titleService.onAwardChanged(event.clubId, event.userId)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onAwardRevoked(event: AwardRevokedEvent) {
        log.info("Award title: award revoked, recomputing: clubId={} userId={}", event.clubId, event.userId)
        titleService.onAwardChanged(event.clubId, event.userId)
    }
}
