package com.clubs.award

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

/** Выданная награда, показывается чипом на карточке участника. `id` даёт организатору удалить её. */
data class AwardDto(
    val id: UUID,
    val emoji: String,
    val label: String
)

/** Опция автокомплита в форме выдачи — ранее использованная пара (emoji, label) в этом клубе. Без id:
 *  одна и та же подпись может встречаться у многих участников, так что подсказка — это значение, а не запись. */
data class AwardSuggestionDto(
    val emoji: String,
    val label: String
)

/** Организатор выдаёт награду «как интересы»: выбирает подсказку или вводит новые emoji + подпись. */
data class GrantAwardRequest(
    @field:NotBlank(message = "Укажите эмодзи награды")
    @field:Size(max = 16, message = "Эмодзи: максимум 16 символов")
    val emoji: String,
    @field:NotBlank(message = "Укажите название награды")
    // 16 — лимит титула Telegram (слайс 4 чат-интеграции): награда целиком помещается в титул.
    @field:Size(max = 16, message = "Название награды: максимум 16 символов")
    val label: String
)
