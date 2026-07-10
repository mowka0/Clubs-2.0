package com.clubs.bot

import com.clubs.award.AwardGrantedEvent
import com.clubs.award.AwardRevokedEvent
import com.clubs.chatlink.MemberTagService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Теги наград (club-chat-link, слайс 4): пересчёт тега в чате на каждое изменение набора
 * наград участника — ПОСЛЕ коммита исходной транзакции (AFTER_COMMIT, как ChatDoorListener:
 * откат выдачи награды не должен оставить тег). Действие — @Async в [MemberTagService],
 * best-effort; полную сверку (включая ручные правки тегов) делает шедулер синхронизации.
 */
@Component
class MemberTagListener(
    private val memberTagService: MemberTagService
) {
    private val log = LoggerFactory.getLogger(MemberTagListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onAwardGranted(event: AwardGrantedEvent) {
        log.info("Member tag: award granted, recomputing: clubId={} userId={}", event.clubId, event.userId)
        memberTagService.onAwardChanged(event.clubId, event.userId)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onAwardRevoked(event: AwardRevokedEvent) {
        log.info("Member tag: award revoked, recomputing: clubId={} userId={}", event.clubId, event.userId)
        memberTagService.onAwardChanged(event.clubId, event.userId)
    }
}
