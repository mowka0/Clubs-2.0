package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * P1b XP / уровни / бейджи — чистая формула. ОТДЕЛЬНЫЙ от Trust канал: глобальный геймификационный
 * счёт ЧЕЛОВЕКА на уровне аккаунта. Только накапливается (нарушенное обещание — 0 XP, никогда не
 * минус) и выводится ПРИ ЧТЕНИИ из того же ledger, что и Trust (без новой таблицы/запроса).
 *
 * XP награждает ТОЛЬКО УЧАСТИЕ (решение 2026-06-14) — с ЕДИНСТВЕННЫМ осознанным исключением
 * (PO 2026-07-22): одноразовый профиль-квест с капом 50 XP (= порог уровня 2), отдельный канал
 * [ProfileQuest] — см. docs/modules/profile-quest.md. В остальном: kept-виды плюс бонус за разнообразие. Старый
 * организаторский бонус (+15 событие / +8 складчина) убран — «результат организатора» — это сигнал
 * о МЕСТЕ, он живёт в треке качества клуба (ось «Надёжность организатора»), а не в личной
 * геймификации. Анти-фарм достаётся бесплатно: у владельца нет строк ledger в собственном клубе
 * (правило 1), поэтому XP он копит только участием в клубах, которыми не владеет.
 *
 * Видимость (§9.1): `self` видит точный XP + прогресс + бейджи; `others` видят только имя уровня
 * (others-tier проекция применяется на границе DTO, не здесь).
 */
object XpPolicy {

    /** XP за один kept-исход в ledger. Broken/neutral-виды дают 0 (XP никогда не убывает). */
    fun kindXp(kind: ReputationKind): Int = when (kind) {
        ReputationKind.ironclad -> 10
        ReputationKind.spontaneous -> 8
        ReputationKind.skladchina_paid -> 3
        else -> 0
    }

    /** Бонус за первый kept-исход в каждом НОВОМ клубе (уникальный club_id с ≥1 kept). */
    const val DIVERSITY_BONUS = 20

    /* ---- Профиль-квест (PO 2026-07-22) — осознанное исключение из «XP только участие».
       Вехи одноразовые (метки users.quest_*_at, V66), кап = сумма трёх = 50 = порог «Свой». ---- */

    /** XP вехи «Город» профиль-квеста. */
    const val QUEST_CITY_XP = 10
    /** XP вехи «Интересы» профиль-квеста. */
    const val QUEST_INTERESTS_XP = 25
    /** XP вехи «О себе» профиль-квеста. Сумма трёх вех = ровно levelThreshold(1) = 50. */
    const val QUEST_BIO_XP = 15

    /**
     * Достигнутые вехи профиль-квеста. Считаются от МЕТОК (users.quest_*_at), а не от текущего
     * содержимого полей: веха одноразовая, XP не отзывается при последующей очистке поля —
     * инвариант «XP не убывает» (AC-P1b-6) сохраняется.
     */
    data class ProfileQuest(val city: Boolean, val interests: Boolean, val bio: Boolean) {
        val completed: Boolean get() = city && interests && bio

        companion object {
            /** Пустой квест: новый пользователь / вехи не найдены. */
            val NONE = ProfileQuest(city = false, interests = false, bio = false)
        }
    }

    /** Профильный XP: сумма достигнутых вех. Кап 50 обеспечен конструкцией — вех ровно три. */
    fun profileXp(quest: ProfileQuest): Int =
        (if (quest.city) QUEST_CITY_XP else 0) +
            (if (quest.interests) QUEST_INTERESTS_XP else 0) +
            (if (quest.bio) QUEST_BIO_XP else 0)

    /** Trust от этого значения и выше делает клуб (с track record) «надёжным» для trust-бейджей. */
    private const val RELIABLE_TRUST = TrustPolicy.RELIABLE_THRESHOLD // 70

    /** 10 имён уровней, индексы 0..9. Индекс 0 (Гость) — пол: с него стартует каждый аккаунт. */
    val LEVEL_NAMES = listOf(
        "Гость", "Свой", "Участник", "Завсегдатай", "Активист",
        "Энтузиаст", "Душа компании", "Столп сообщества", "Легенда", "Амбассадор"
    )

    /** Порог XP для достижения уровня с индексом [i] (0-based): round(50·i^2). Уровень 0 = 0 XP.
     *  Кривая откалибрована 2026-06-14 (40·i^1.85 → 50·i^2), чтобы высокие уровни были реже. */
    fun levelThreshold(i: Int): Int = (50.0 * i.toDouble().pow(2.0)).roundToInt()

    /** 0-based индекс уровня для данного XP — наивысший уровень, чей порог достигнут. */
    fun levelIndexFor(xp: Int): Int =
        LEVEL_NAMES.indices.last { xp >= levelThreshold(it) }

    /** Счётчики/позиции для расчёта суммы XP и бейджей; считаются один раз из ledger. */
    data class XpStats(
        val ironcladCount: Int,
        val spontaneousCount: Int,
        val skladchinaPaidCount: Int,
        /** уникальные club_id с ≥1 kept-исходом — множитель разнообразия. */
        val distinctKeptClubs: Int,
        /** клубы с показываемым track record (outcomeCount ≥ N) и Trust ≥ RELIABLE_TRUST. */
        val reliableClubs: Int,
        /** максимальный клубный Trust среди клубов с показываемым track record (0, если таких нет). */
        val maxTrustWithRecord: Int
    ) {
        val totalKept: Int get() = ironcladCount + spontaneousCount + skladchinaPaidCount
    }

    /** Суммарный XP УЧАСТИЯ = Σ XP по kept-видам + бонус разнообразия за каждый уникальный kept-клуб. */
    fun totalXp(stats: XpStats): Int =
        stats.ironcladCount * kindXp(ReputationKind.ironclad) +
            stats.spontaneousCount * kindXp(ReputationKind.spontaneous) +
            stats.skladchinaPaidCount * kindXp(ReputationKind.skladchina_paid) +
            stats.distinctKeptClubs * DIVERSITY_BONUS

    /** Полный XP аккаунта = участие (ledger) + профиль-квест (одноразовые вехи). */
    fun totalXp(stats: XpStats, quest: ProfileQuest): Int = totalXp(stats) + profileXp(quest)

    enum class BadgeFamily { PARTICIPATION, DIVERSITY, TRUST, PROFILE }

    /** Бейдж = пройденный порог (не сырой счёт). Пороги поддаются калибровке. */
    data class Badge(
        val id: String,
        val name: String,
        val family: BadgeFamily,
        val earned: (XpStats) -> Boolean
    )

    // Пороги откалиброваны 2026-06-14, чтобы бейджи стали реже. `first_step` и `reliable_1` намеренно
    // остаются достижимыми (новичку нужна видимая стартовая цель); остальные — настоящие вехи.
    val BADGES: List<Badge> = listOf(
        Badge("first_step", "Первый шаг", BadgeFamily.PARTICIPATION) { it.totalKept >= 1 },
        Badge("ironclad_20", "Железобетон", BadgeFamily.PARTICIPATION) { it.ironcladCount >= 20 },
        Badge("spontaneous_10", "Лёгок на подъём", BadgeFamily.PARTICIPATION) { it.spontaneousCount >= 10 },
        Badge("payer_8", "Надёжный плательщик", BadgeFamily.PARTICIPATION) { it.skladchinaPaidCount >= 8 },
        Badge("diverse_5", "Разносторонний", BadgeFamily.DIVERSITY) { it.distinctKeptClubs >= 5 },
        Badge("diverse_8", "Вездесущий", BadgeFamily.DIVERSITY) { it.distinctKeptClubs >= 8 },
        Badge("reliable_1", "Надёжный", BadgeFamily.TRUST) { it.reliableClubs >= 1 },
        Badge("rock_solid", "Кремень", BadgeFamily.TRUST) { it.maxTrustWithRecord >= 95 },
        Badge("reliable_5", "Столп доверия", BadgeFamily.TRUST) { it.reliableClubs >= 5 }
    )

    /** Бейдж «Визитка» — профиль-квест целиком. Предикат-заглушка: условие — от квеста,
     *  не от статов участия, и живёт в [badgesFor] с двумя аргументами. */
    val PROFILE_CARD = Badge("profile_card", "Визитка", BadgeFamily.PROFILE) { false }

    /** Заработанные бейджи для данных статов, в порядке объявления. */
    fun badgesFor(stats: XpStats): List<Badge> = BADGES.filter { it.earned(stats) }

    /** Бейджи с учётом профиль-квеста: участие + «Визитка» при полностью заполненном профиле. */
    fun badgesFor(stats: XpStats, quest: ProfileQuest): List<Badge> =
        badgesFor(stats) + listOfNotNull(PROFILE_CARD.takeIf { quest.completed })

    fun isReliable(trust: Int): Boolean = trust >= RELIABLE_TRUST
}
