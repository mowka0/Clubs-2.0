package com.clubs.feedback.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Тело POST /api/feedback — сообщение из формы «Сообщить о проблеме». */
data class SubmitFeedbackRequest(
    // Лимит 2000 — с запасом меньше телеграмного потолка 4096 символов на сообщение:
    // остаток уходит под служебную шапку и подпись отправителя.
    @field:NotBlank
    @field:Size(max = 2000)
    val message: String,

    /** Route, с которого открыта форма — контекст «где воспроизвелось». Опционален. */
    @field:Size(max = 200)
    val page: String? = null,
)
