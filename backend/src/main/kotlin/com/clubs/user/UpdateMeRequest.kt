package com.clubs.user

import jakarta.validation.constraints.Size

/**
 * Full replace of the user-editable profile fields (the edit form always sends
 * its complete state). Blank country/city/bio clear the field; [interests] is
 * normalized + deduped server-side. Name/avatar/@username are NOT here — they
 * are synced from Telegram on every auth and would be overwritten.
 */
data class UpdateMeRequest(
    @field:Size(max = 8)
    val country: String? = null,

    @field:Size(max = 255)
    val city: String? = null,

    @field:Size(max = 280)
    val bio: String? = null,

    val interests: List<String> = emptyList()
)
