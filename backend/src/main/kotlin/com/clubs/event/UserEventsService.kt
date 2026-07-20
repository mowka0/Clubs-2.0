package com.clubs.event

import com.clubs.common.dto.PageResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserEventsService(
    private val eventRepository: EventRepository,
    private val eventMapper: EventMapper
) {

    private val log = LoggerFactory.getLogger(UserEventsService::class.java)

    @Transactional(readOnly = true)
    fun getMyEvents(userId: UUID, page: Int, size: Int): PageResponse<MyEventListItemDto> {
        val pageResult = eventRepository.findMyFeed(userId, page, size)
        val historyCount = pageResult.content.count { it.isHistory }
        log.info(
            "My events feed: userId={} page={} size={} returned={} history={}",
            userId, page, size, pageResult.content.size, historyCount
        )
        return PageResponse(
            content = pageResult.content.map(eventMapper::toMyFeedItemDto),
            totalElements = pageResult.totalElements,
            totalPages = pageResult.totalPages,
            page = pageResult.page,
            size = pageResult.size
        )
    }
}
