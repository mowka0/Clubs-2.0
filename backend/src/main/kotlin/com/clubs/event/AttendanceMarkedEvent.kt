package com.clubs.event

import java.util.UUID

/**
 * Публикуется, когда организатор отмечает посещаемость события (ATT-3). Слушатель бота реагирует
 * AFTER_COMMIT, чтобы отправить DM участникам, которые СТАЛИ отсутствующими именно в этой отметке
 * ([newlyAbsentUserIds]), предложив им оспорить решение. Id пользователей переносятся вместе с
 * событием (вычислены синхронно в markAttendance), а не запрашиваются заново по посещаемости —
 * поэтому повторная отметка не шлёт DM повторно участникам, уже отмеченным отсутствующими (F5-15.2).
 * См. AttendanceService.markAttendance и reputation-v2.md.
 */
data class AttendanceMarkedEvent(val eventId: UUID, val newlyAbsentUserIds: List<UUID>)
