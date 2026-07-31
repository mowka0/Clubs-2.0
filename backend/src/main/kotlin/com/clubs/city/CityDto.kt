package com.clubs.city

import java.util.UUID

/**
 * Город для пикера. Координаты и население наружу не отдаём — UI их не использует,
 * в БД они лежат под будущее «клубы рядом».
 */
data class CityDto(
    val id: UUID,
    val name: String,
    val region: String?,
    val needsRegion: Boolean,
    val countryCode: String,
    val isFeatured: Boolean,
    /** В городе есть хотя бы один активный клуб — витрина показывает такие города до ввода запроса. */
    val hasClubs: Boolean
)
