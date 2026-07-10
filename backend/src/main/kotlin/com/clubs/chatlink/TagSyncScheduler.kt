package com.clubs.chatlink

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Полная синхронизация тег↔награда (слайс 4, требование PO 2026-07-10: «полная синхронность»):
 * раз в период по каждому клубу с включёнными тегами сверяет фактические теги участников чата
 * с наградами приложения и заполняет пустую сторону (правила — в [MemberTagService.syncClub]).
 * Ловит то, что события не покрывают: участник вступил в чат ПОСЛЕ выдачи награды, ручные
 * теги организатора, сбои Telegram при событийном обновлении.
 */
@Component
class TagSyncScheduler(
    private val chatLinkRepository: ChatLinkRepository,
    private val memberTagService: MemberTagService
) {
    private val log = LoggerFactory.getLogger(TagSyncScheduler::class.java)

    // Период сверки конфигурируем (chatlink.tag-sync-ms, дефолт 30 сек — требование PO);
    // fixedDelay: следующий проход стартует после завершения предыдущего, наложений нет.
    @Scheduled(fixedDelayString = "\${chatlink.tag-sync-ms:30000}")
    fun sync() {
        chatLinkRepository.findAllWithAwardTags().forEach { link ->
            if (!link.botStatus.isInChat || !link.canManageTags) return@forEach
            try {
                memberTagService.syncClub(link)
            } catch (e: Exception) {
                // Сбой одного клуба не должен останавливать сверку остальных.
                log.warn("Tag sync failed for clubId={}: {}", link.clubId, e.message)
            }
        }
    }
}
