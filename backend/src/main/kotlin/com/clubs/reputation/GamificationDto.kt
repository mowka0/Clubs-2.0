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
    val badges: List<BadgeDto>,
    /** Профиль-квест (карточка «Прокачай профиль»): какие вехи достигнуты. См. profile-quest.md. */
    val quest: ProfileQuestDto
)

/** Вехи профиль-квеста для карточки в профиле. Done-флаги — от одноразовых меток, не от текущего содержимого полей. */
data class ProfileQuestDto(
    val cityDone: Boolean,
    val interestsDone: Boolean,
    val bioDone: Boolean,
    /** Все три вехи достигнуты — карточка-квест не показывается, бейдж «Визитка» получен. */
    val completed: Boolean
)

data class BadgeDto(
    val id: String,
    val name: String,
    val family: String
)
