package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * P1b XP / уровни / бейджи — чистая формула. ОТДЕЛЬНЫЙ от Trust канал: глобальный геймификационный
 * счёт ЧЕЛОВЕКА на уровне аккаунта. Только накапливается (нарушенное обещание — 0 XP, никогда не
 * минус) и выводится ПРИ ЧТЕНИИ из того же ledger, что и Trust (без новой таблицы/запроса).
 *
 * XP награждает ТОЛЬКО УЧАСТИЕ (решение 2026-06-14): kept-виды плюс бонус за разнообразие. Старый
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

    /** Суммарный XP = Σ XP по kept-видам + бонус разнообразия за каждый уникальный kept-клуб. */
    fun totalXp(stats: XpStats): Int =
        stats.ironcladCount * kindXp(ReputationKind.ironclad) +
            stats.spontaneousCount * kindXp(ReputationKind.spontaneous) +
            stats.skladchinaPaidCount * kindXp(ReputationKind.skladchina_paid) +
            stats.distinctKeptClubs * DIVERSITY_BONUS

    enum class BadgeFamily { PARTICIPATION, DIVERSITY, TRUST }

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

    /** Заработанные бейджи для данных статов, в порядке объявления. */
    fun badgesFor(stats: XpStats): List<Badge> = BADGES.filter { it.earned(stats) }

    fun isReliable(trust: Int): Boolean = trust >= RELIABLE_TRUST
}
