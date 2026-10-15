package com.clubs.analytics.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.util.UUID

/** Пачка событий от Mini App (product-analytics.md § 4). Автор — только из JWT, в теле его нет. */
data class TrackRequest(
    @field:NotEmpty(message = "events must not be empty")
    @field:Size(max = TrackRequest.MAX_EVENTS, message = "events: at most ${TrackRequest.MAX_EVENTS} per request")
    @field:Valid
    val events: List<TrackEventRequest>,
) {
    companion object {
        // Потолок пачки: клиент копит до 20 и шлёт раз в 3 с — больше одной пачки на вспышку навигации не нужно.
        const val MAX_EVENTS = 20
    }
}

data class TrackEventRequest(
    @field:NotBlank(message = "kind must not be blank")
    @field:Size(max = 48, message = "kind must be at most 48 characters")
    val kind: String,
    /** Метка для группировки, не доступ: существование и членство не проверяются (§ 6). */
    val clubId: UUID? = null,
    /** Плоский объект: значения — строка, число или boolean; вложенность отклоняется сервисом. */
    val props: Map<String, Any?>? = null,
)
