package com.clubs.event

import java.util.UUID

/**
 * Публикуется (один раз на событие), когда посещаемость финализирована, после 48-часового
 * окна спора. Слушатель репутации с малой задержкой обрабатывает его в ledger; ежечасный опрос
 * служит надёжным резервным механизмом. См. docs/modules/reputation-v2.md.
 */
data class AttendanceFinalizedEvent(val eventId: UUID)
