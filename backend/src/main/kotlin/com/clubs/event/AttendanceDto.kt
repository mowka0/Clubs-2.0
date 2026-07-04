package com.clubs.event

import jakarta.validation.constraints.Size
import java.util.UUID

data class AttendanceEntryRequest(
    val userId: UUID,
    val attended: Boolean
)

data class MarkAttendanceRequest(
    val attendance: List<AttendanceEntryRequest>
)

data class AttendanceResultDto(
    val eventId: UUID,
    val markedCount: Int
)

data class ResolveDisputeRequest(
    val attended: Boolean
)

/** Необязательная свободная заметка, которую участник прикладывает, оспаривая свою посещаемость. */
data class DisputeAttendanceRequest(
    @field:Size(max = 500)
    val note: String? = null
)

/**
 * F5-04: СОБСТВЕННОЕ состояние посещаемости вызывающего для события (GET /api/events/{id}/my-attendance).
 * Читается без членства в клубе, чтобы участник, покинувший клуб, всё ещё мог добраться до UI спора
 * после deep-link DM. [canDispute] считается на сервере (окно открыто И attendance=absent И спор ещё
 * не терминален) — фронт завязывает на него кнопку «Оспорить».
 */
data class MyAttendanceDto(
    val attendance: String?,        // attended | absent | disputed | null
    val attendanceMarked: Boolean,
    val attendanceFinalized: Boolean,
    val disputeTerminal: Boolean,
    val canDispute: Boolean,
    val disputeNote: String?        // собственная заметка вызывающего (только его строка)
)
