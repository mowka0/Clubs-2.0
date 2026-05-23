package com.clubs.skladchina

import com.clubs.generated.jooq.enums.SkladchinaStatus
import java.util.UUID

/**
 * Abstraction over bot notifications for skladchina lifecycle events.
 * Implementation lives in `com.clubs.bot` to keep skladchina module
 * decoupled from Telegram client.
 */
interface SkladchinaNotifier {

    /** Notify each participant via DM with payment link + inline button to open Mini App. */
    fun sendCreated(skladchina: Skladchina, clubName: String, participantUserIds: List<UUID>)

    /** Notify the creator with collection summary. */
    fun sendClosed(
        skladchina: Skladchina,
        clubName: String,
        finalStatus: SkladchinaStatus,
        collectedKopecks: Long,
        paidCount: Int,
        participantCount: Int
    )
}
