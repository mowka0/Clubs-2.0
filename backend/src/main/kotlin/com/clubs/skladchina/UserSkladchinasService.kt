package com.clubs.skladchina

import com.clubs.common.dto.PageResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserSkladchinasService(
    private val skladchinaRepository: SkladchinaRepository,
    private val mapper: SkladchinaMapper
) {
    private val log = LoggerFactory.getLogger(UserSkladchinasService::class.java)

    @Transactional(readOnly = true)
    fun getMySkladchinas(userId: UUID, page: Int, size: Int): PageResponse<MySkladchinaListItemDto> {
        val pageResult = skladchinaRepository.findMyFeed(userId, page, size)
        log.info("My skladchina feed: userId={} page={} size={} returned={}",
            userId, page, size, pageResult.content.size)
        return PageResponse(
            content = pageResult.content.map { mapper.toMyFeedItemDto(it, userId) },
            totalElements = pageResult.totalElements,
            totalPages = pageResult.totalPages,
            page = pageResult.page,
            size = pageResult.size
        )
    }

    @Transactional(readOnly = true)
    fun countActionRequired(userId: UUID): ActionRequiredCountDto =
        ActionRequiredCountDto(skladchinaRepository.countActionRequired(userId))
}
