package com.clubs.reputation

/**
 * Панель геймификации авторизованного пользователя (вид `self`, H8): точный XP, текущий уровень +
 * прогресс до следующего, и полученные бейджи. Вычисляется при чтении из леджера [XpService].
 * Проекция `others` (только название уровня) — отдельный, более узкий вид, применяемый там, где
 * строятся карточки участников.
 */
data class GamificationDto(
    val xp: Int,
    /** Номер уровня, отсчёт с 1 (1..10). */
    val level: Int,
    val levelName: String,
    /** null, если пользователь на максимальном уровне. */
    val nextLevelName: String?,
    /** XP, накопленный внутри текущего уровня (xp − порог текущего уровня). */
    val xpIntoLevel: Int,
    /** Диапазон XP текущего уровня (следующий порог − текущий порог); null на максимальном уровне. */
    val xpSpanToNext: Int?,
    val badges: List<BadgeDto>
)

data class BadgeDto(
    val id: String,
    val name: String,
    val family: String
)
