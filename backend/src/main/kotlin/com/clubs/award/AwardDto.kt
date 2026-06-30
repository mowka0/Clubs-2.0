package com.clubs.award

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

/** A granted award shown as a chip on the member card. `id` lets the organizer remove it. */
data class AwardDto(
    val id: UUID,
    val emoji: String,
    val label: String
)

/** An autocomplete option in the grant form — a previously-used (emoji, label) in this club. No id:
 *  the same label may exist for many members, so a suggestion is a value, not a single record. */
data class AwardSuggestionDto(
    val emoji: String,
    val label: String
)

/** Organizer grants an award «как интересы»: pick a suggestion or type a fresh emoji + label. */
data class GrantAwardRequest(
    @field:NotBlank(message = "Укажите эмодзи награды")
    @field:Size(max = 16, message = "Эмодзи: максимум 16 символов")
    val emoji: String,
    @field:NotBlank(message = "Укажите название награды")
    @field:Size(max = 40, message = "Название награды: максимум 40 символов")
    val label: String
)
