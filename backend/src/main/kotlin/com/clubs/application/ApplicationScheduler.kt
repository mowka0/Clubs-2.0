package com.clubs.application

import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.generated.jooq.tables.references.APPLICATIONS
import com.clubs.generated.jooq.tables.references.CLUBS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Component
class ApplicationScheduler(private val dsl: DSLContext) {

    private val log = LoggerFactory.getLogger(ApplicationScheduler::class.java)

    @Scheduled(fixedDelay = 3_600_000) // every 1 hour
    @Transactional
    fun autoRejectExpiredApplications() {
        val cutoff = OffsetDateTime.now().minusHours(48)

        val expired = dsl.selectFrom(APPLICATIONS)
            .where(
                APPLICATIONS.STATUS.eq(ApplicationStatus.pending)
                    .and(APPLICATIONS.CREATED_AT.lessOrEqual(cutoff))
            )
            .fetch()

        if (expired.isEmpty()) return

        val clubIds = expired.map { it.clubId!! }.distinct()

        // Auto-reject all expired applications
        dsl.update(APPLICATIONS)
            .set(APPLICATIONS.STATUS, ApplicationStatus.auto_rejected)
            .set(APPLICATIONS.RESOLVED_AT, OffsetDateTime.now())
            .where(
                APPLICATIONS.STATUS.eq(ApplicationStatus.pending)
                    .and(APPLICATIONS.CREATED_AT.lessOrEqual(cutoff))
            )
            .execute()

        log.info("Auto-rejected ${expired.size} applications: IDs = ${expired.map { it.id }}")

        // Decrease activity_rating for each club with auto-rejected applications
        clubIds.forEach { clubId ->
            dsl.update(CLUBS)
                .set(CLUBS.ACTIVITY_RATING, org.jooq.impl.DSL.greatest(CLUBS.ACTIVITY_RATING.minus(5), org.jooq.impl.DSL.`val`(0)))
                .where(CLUBS.ID.eq(clubId))
                .execute()
            log.info("Decreased activity_rating by 5 for club $clubId due to auto-rejected applications")
        }
    }
}
