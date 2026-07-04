package com.clubs.event

import java.util.UUID

data class CastVoteRequest(
    val vote: String  // "going" | "maybe" | "not_going"
)

data class VoteResponseDto(
    val eventId: UUID,
    val vote: String,
    val goingCount: Int,
    val maybeCount: Int,
    val notGoingCount: Int
)

data class MyVoteDto(
    val vote: String?
)

/**
 * Один проголосовавший в списке события "кто идёт".
 * [status] — текущее намерение пользователя: финальный статус Этапа 2, если он есть
 * (confirmed | waitlisted | declined), иначе голос Этапа 1
 * (going | maybe | not_going).
 * [attendance] — отметка после события, как только организатор её проставил
 * (attended | absent | disputed), иначе null. Управляет UI оспаривания: отсутствующий
 * участник может оспорить отметку; организатор разрешает оспоренную.
 */
data class EventResponderDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val status: String,
    val attendance: String?,
    // Опциональная свободная заметка, которую оставил участник при оспаривании (показывается организатору).
    val disputeNote: String?
)
