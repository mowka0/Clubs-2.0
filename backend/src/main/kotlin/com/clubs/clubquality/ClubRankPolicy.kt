package com.clubs.clubquality

import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.pow

/**
 * L3 скрытый ранг — чистая формула (сердце club-quality-трека). Композит из четырёх осей по
 * distinct-credible-аккаунтам минус owner-blind негативы, за гейтом credibility-взвешенного
 * min-K-порога. INTERNAL: ничто отсюда никогда не сериализуется; единственная внешне видимая
 * производная — булев бейдж «★ Топ-5 в категории».
 *
 * Контракт дизайна: docs/backlog/club-quality-gamification.md §1–8, docs/modules/club-quality.md §10.
 *
 * **Анти-фарм каркас (каждая константа ему служит):**
 *  1. Данные авторства владельца никогда не попадают в L3 — репозиторий подаёт только сигналы,
 *     порождённые участниками.
 *  2. Взвешивание по distinct-CREDIBLE-аккаунтам: каждая ось — `Σ credibility(аккаунт)`,
 *     никогда не сырые счётчики.
 *  3. Абсолюты, никогда не доли (кольцо ботов тривиально выдаёт 100% любого %).
 *  4. Recency-decay по времени поведения (`occurred_at`) — ничего не копится в зачёт навечно.
 *  5. Credibility-ВЗВЕШЕННЫЙ гейт существования: кольцо дешёвых аккаунтов не может сфабриковать ранг.
 *  6. Схлопывание owner-концентрации + per-owner cap в категории: один оператор не может сам
 *     заселить категорию (Sybil-вектор «фабрикация категории»).
 *
 * **PROVISIONAL:** веса/якоря/пороги — принципиальные дефолты, НЕ калиброванные: калибровка на
 * проде из ~10 клубов невозможна. Живут здесь, чтобы будущая калибровка трогала один файл, без SQL.
 */
object ClubRankPolicy {

    // ---- Гейт существования (есть ли у клуба ранг вообще?) ----

    /**
     * Credibility-ВЗВЕШЕННЫЙ min-K: клуб ранжируется, только если `Σ credibility(credibleCore) ≥ EFFECTIVE_K`.
     * Взвешенный (не поголовный счёт): свежая одноклубная марионетка вносит ~0.24, так что кольцу нужно
     * ~22+ аккаунтов, а не 8 — единственное число, реально масштабирующее цену атаки. Ниже гейта ⇒ UNRANKED.
     */
    const val EFFECTIVE_K = 8.0

    /** Пол вклада credibility одного аккаунта; аккаунт с сырой credibility ниже этого порога
     *  вообще не учитывается — ни в гейте, ни в одной из осей. */
    const val CRED_MIN = 0.2

    // ---- Recency-decay (математика TrustPolicy: 0.5^(возраст/halfLife), даты из будущего → вес 1.0) ----

    /** Позитивы теряют половину веса за столько дней (слава утекает медленно). */
    const val HALF_LIFE_POS_DAYS = 120.0

    /** Негативы затухают быстрее позитивов — клапан восстановления (исправившийся клуб не наказан навечно). */
    const val HALF_LIFE_NEG_DAYS = 90.0

    // ---- Абсолютные полы по осям (ось ниже своего пола даёт 0, а не долю) ----

    const val PAY_MIN = 3 // минимум различных плательщиков (Stars)
    const val VOTE_MIN = 8 // минимум различных голосовавших участников
    const val EVENT_MIN = 2 // минимум событий с ≥4 различными квалифицированными посетителями

    // ---- Квалификация «костяка» (анти-фарм форма сигнала ядра) ----

    /** Участник ядра должен посетить минимум столько РАЗНЫХ событий. */
    const val CORE_MIN_EVENTS = 2

    /** Квалифицирующие посещения должны быть разнесены минимум на столько дней — фарм одним
     *  batch-днём (всех отметили за один вечер) не фабрикует разнообразие. */
    const val MIN_EVENT_GAP_DAYS = 7L

    /** Событие идёт в зачёт LiveActivity только при минимум стольких различных квалифицированных посетителях. */
    const val EVENT_MIN_ATTENDEES = 4

    // ---- Окна чтения ----

    /** DemandResponsiveness считает голосовавших участников внутри этого скользящего окна. */
    const val DEMAND_WINDOW_DAYS = 90L

    /** Сигналы старше этого не читаются вовсе (дешёвый скан; вес к тому моменту всё равно < 0.13). */
    const val HARD_CUTOFF_DAYS = 365L

    /** Плательщик, ушедший в течение стольких дней после оплаты, — сигнатура скама «заплатил и исчез». */
    const val SCAM_LEFT_WINDOW_DAYS = 14L

    // ---- Веса композита (платный клуб). Деньги + ядро = 65% — самые дорогие для фарма оси. ----

    const val W_DIVERSITY = 0.35 // вес оси CoreDiversity (разнообразие ядра)
    const val W_PAYING = 0.30 // вес оси PayingRetention (удержание плательщиков)
    const val W_DEMAND = 0.20 // вес оси DemandResponsiveness (отклик на спрос)
    const val W_ACTIVITY = 0.15 // вес оси LiveActivity (живая активность)

    // Бесплатный клуб: PayingRetention выключена; её 0.30 пропорционально перераспределяется на три остальные.
    const val W_DIVERSITY_FREE = W_DIVERSITY / (1 - W_PAYING) // 0.50
    const val W_DEMAND_FREE = W_DEMAND / (1 - W_PAYING) // ≈0.286
    const val W_ACTIVITY_FREE = W_ACTIVITY / (1 - W_PAYING) // ≈0.214

    // ---- Якоря насыщающей нормализации (чтобы оси в разных единицах складывались соизмеримо) ----
    // norm(x) = 1 − 0.5^(x/anchor): достигает 0.5 в точке якоря, насыщается к 1.

    const val ANCHOR_DIVERSITY = 12.0 // якорь оси CoreDiversity (взвешенная сумма, дающая norm = 0.5)
    const val ANCHOR_PAYING = 8.0 // якорь оси PayingRetention
    const val ANCHOR_DEMAND = 12.0 // якорь оси DemandResponsiveness
    const val ANCHOR_ACTIVITY = 6.0 // якорь оси LiveActivity

    /** Плательщик, ушедший ≤14 дней после оплаты (сигнатура скама), учитывается с этим коэффициентом. */
    const val SCAM_PAYER_FACTOR = 0.5

    // ---- Корзины веса credibility ----

    const val AGE_W_MATURE = 1.0 // возраст аккаунта ≥180 дней
    const val AGE_W_ESTABLISHED = 0.8 // 90–180 дней
    const val AGE_W_RECENT = 0.6 // 30–90 дней
    const val AGE_W_FRESH = 0.4 // <30 дней

    const val SIGNAL_W_BASE = 0.6 // базовый вес профильных сигналов аккаунта
    const val SIGNAL_W_USERNAME = 0.2 // надбавка за наличие username
    const val SIGNAL_W_AVATAR = 0.2 // надбавка за наличие аватара

    const val FOOTPRINT_W_BROAD = 1.0 // футпринт у ≥3 различных владельцев клубов
    const val FOOTPRINT_W_SOME = 0.85 // 2 владельца
    const val FOOTPRINT_W_SINGLE = 0.6 // 1 владелец (sock-puppet сидит в клубах одного оператора)

    /** Если ≥ этой доли футпринта аккаунта приходится на клубы владельца ЭТОГО клуба — это не независимое
     *  свидетельство качества → credibility прижимается к CRED_MIN (лёгкая проверка co-occurrence). */
    const val OWNER_CONCENTRATION_THRESHOLD = 0.6

    // ---- Негативные штрафы (вычитаются из базы [0,1], с потолком, с затуханием) ----
    // Величины — на нормализованной шкале [0,1]: пара инцидентов вминает скор, но не обнуляет его автоматом.

    const val DISPUTE_W = 0.05 // штраф за один диспут посещаемости
    const val DISPUTE_CAP = 0.30 // потолок суммарного штрафа за диспуты
    const val GHOST_W = 0.07 // штраф за один ghosting-инцидент
    const val GHOST_CAP = 0.40 // потолок суммарного штрафа за ghosting
    const val SOFT_W = 0.03 // за каждый из мягких сигналов: auto-reject / skladchina-ghost
    const val SOFT_CAP = 0.20 // общий потолок на два мягких штрафа

    // ---- Множители ----

    /** Демпфер «слишком чисто при объёме»: заметный клуб с нулём диспутов/ghosting/оттока за окно
     *  статистически подозрителен (гладкое кольцо). Один триггер, −0.1. */
    const val ANOMALY_CLEAN_MIN_CORE = 10 // размер ядра, с которого «слишком чисто» становится подозрительным
    const val ANOMALY_STEP = 0.1 // шаг снижения множителя за сработавший триггер аномалии
    const val ANOMALY_FLOOR = 0.7 // нижняя граница anomaly-множителя

    /** Клуб моложе этого возраста получает пропорциональный вес (тонкая история — не полное свидетельство). */
    const val TENURE_FULL_DAYS = 90.0

    // ---- Гейты бейджа «★ Топ-5 в категории» ----

    /** Категории нужно минимум столько ранжированных клубов (после per-owner cap), чтобы бейдж вообще
     *  появился — нельзя быть «топ-5» из трёх. Держит бейдж реальным отбором, а не призом за участие.
     *  Размер выбран намеренно так, чтобы на маленьком проде бейдж был РЕДКИМ (честное отсутствие
     *  лучше фальшивых звёзд). */
    const val MIN_CATEGORY_SIZE = 6

    /** Абсолютный пол скора: даже на позиции ≤5 клуб, едва переваливший K-гейт, оказывается ниже
     *  и бейджа не получает. Бейдж значит «хороший И топ категории», а не «топ жидкой категории». */
    const val BADGE_SCORE_FLOOR = 0.20

    /** Клуб на позиции ≤5 обязан обгонять шестой клуб минимум на этот отрыв; если #5 и #6
     *  неразличимы — реального отбора не случилось → на границе бейджа нет. */
    const val SELECTIVITY_EPS = 0.05

    /** Пока по всему проду ранжировано меньше стольких клубов, бейдж подавлен у ВСЕХ клубов —
     *  явный kill-switch «ранг ещё не осмыслен» (отдельный от деплойного feature-флага). */
    const val GLOBAL_RANK_FLOOR = 8

    /** Схлопывание по owner-концентрации (co-occurrence) — защита v1; полный кросс-клубовый граф —
     *  задокументированная заглушка (=1.0), пока её не активирует реальная атака (дизайн §5). */
    const val CO_OCCURRENCE_COLLAPSE = 1.0

    /** Вторичного рынка владения клубами пока нет → transfer probation — задокументированная заглушка. */
    const val TRANSFER_PROBATION = 1.0

    // ---- Чистые хелперы ----

    /** Вес recency-decay в (0,1]. Строки с датой из будущего (перекос часов) считаются свежими (вес 1). */
    fun decay(occurredAt: OffsetDateTime, now: OffsetDateTime, halfLifeDays: Double): Double {
        val ageDays = (now.toEpochSecond() - occurredAt.toEpochSecond()) / 86_400.0
        return 0.5.pow((if (ageDays < 0.0) 0.0 else ageDays) / halfLifeDays)
    }

    /** Насыщающая нормализация в [0,1): достигает 0.5 в точке [anchor]. */
    fun norm(x: Double, anchor: Double): Double = 1.0 - 0.5.pow(x / anchor)

    private fun ageW(createdAt: OffsetDateTime, now: OffsetDateTime): Double {
        val ageDays = (now.toEpochSecond() - createdAt.toEpochSecond()) / 86_400.0
        return when {
            ageDays >= 180 -> AGE_W_MATURE
            ageDays >= 90 -> AGE_W_ESTABLISHED
            ageDays >= 30 -> AGE_W_RECENT
            else -> AGE_W_FRESH
        }
    }

    private fun signalW(input: CredibilityInput): Double =
        SIGNAL_W_BASE +
            (if (input.hasUsername) SIGNAL_W_USERNAME else 0.0) +
            (if (input.hasAvatar) SIGNAL_W_AVATAR else 0.0)

    private fun footprintW(footprintByOwner: Map<UUID, Int>): Double =
        when (footprintByOwner.size) {
            0, 1 -> FOOTPRINT_W_SINGLE
            2 -> FOOTPRINT_W_SOME
            else -> FOOTPRINT_W_BROAD
        }

    /** Доля клубов с kept-outcome у аккаунта, принадлежащих [ownerId]. 0, если у аккаунта нет
     *  футпринта (участник, у которого нигде ещё нет kept-outcome). */
    private fun ownerConcentration(footprintByOwner: Map<UUID, Int>, ownerId: UUID): Double {
        val total = footprintByOwner.values.sum()
        if (total == 0) return 0.0
        return (footprintByOwner[ownerId] ?: 0).toDouble() / total
    }

    /**
     * Вес credibility одного аккаунта для ЭТОГО клуба, в [0,1]. Аккаунт, чей футпринт доминируется
     * клубами этого же владельца, прижимается к [CRED_MIN] (не независимое свидетельство). В остальных
     * случаях результат НЕ подрезается снизу — вызывающий сам выкидывает аккаунты ниже [CRED_MIN]
     * из гейта/осей.
     */
    fun credibility(input: CredibilityInput, ownerId: UUID, now: OffsetDateTime): Double {
        if (ownerConcentration(input.footprintByOwner, ownerId) >= OWNER_CONCENTRATION_THRESHOLD) {
            return CRED_MIN
        }
        return (ageW(input.createdAt, now) * signalW(input) * footprintW(input.footprintByOwner))
            .coerceAtMost(1.0)
    }

    /** Затухающая, credibility-взвешенная сумма по оси distinct-аккаунтов после per-account пола
     *  credibility. [extraFactor] — поправка на аккаунт (например, scam-плательщик ×0.5); дефолт 1.0. */
    private fun weightedAxis(
        accounts: List<AccountOutcome>,
        cred: Map<UUID, Double>,
        now: OffsetDateTime,
        extraFactor: (UUID) -> Double = { 1.0 },
    ): Double = accounts.sumOf { a ->
        val c = cred[a.userId] ?: 0.0
        if (c < CRED_MIN) 0.0 else c * decay(a.occurredAt, now, HALF_LIFE_POS_DAYS) * extraFactor(a.userId)
    }

    private fun decayedSum(times: List<OffsetDateTime>, now: OffsetDateTime, halfLife: Double): Double =
        times.sumOf { decay(it, now, halfLife) }

    /**
     * Считает L3-ранг одного клуба. [credibilityInputs] ключуется по userId (общие между клубами).
     * Возвращает сохраняемый результат; [ClubRank.isRanked] = прошёл credibility-взвешенный K-гейт.
     */
    fun computeRank(
        signals: ClubRankSignals,
        credibilityInputs: Map<UUID, CredibilityInput>,
        now: OffsetDateTime,
    ): ClubRank {
        val cred: Map<UUID, Double> = credibilityInputs.values.associate {
            it.userId to credibility(it, signals.ownerId, now)
        }

        // Гейт существования: Σ credibility по credible-ядру (каждый аккаунт ≥ CRED_MIN).
        val effectiveK = signals.core.sumOf { (cred[it.userId] ?: 0.0).let { c -> if (c < CRED_MIN) 0.0 else c } }
        val isRanked = effectiveK >= EFFECTIVE_K

        if (!isRanked) {
            return ClubRank(signals.clubId, signals.ownerId, signals.category, 0.0, false, effectiveK)
        }

        // Оси — абсолюты, с затуханием, credibility-взвешенные; ниже пола оси ⇒ 0.
        // У Diversity нет отдельного поголовного пола: credibility-взвешенный гейт (effectiveK ≥
        // EFFECTIVE_K) уже гарантирует реальное ядро, а пол по счётчику вернул бы ровно тот
        // count-гейт, который дизайн отверг.
        val diversityRaw = weightedAxis(signals.core, cred, now)
        // payers = аккаунты с первой оплатой (подпиской); renewers = непересекающийся бонус лояльности
        // за продления. Популяции разделены в репозитории, поэтому продливший не считается дважды.
        val payingRaw = if (signals.isPaid && signals.payers.size >= PAY_MIN) {
            weightedAxis(signals.payers, cred, now) { uid ->
                if (uid in signals.scamPayers) SCAM_PAYER_FACTOR else 1.0
            } + weightedAxis(signals.renewers, cred, now)
        } else {
            0.0
        }
        val demandRaw = if (signals.voters.size >= VOTE_MIN) weightedAxis(signals.voters, cred, now) else 0.0
        val activityRaw = if (signals.qualityEvents.size >= EVENT_MIN) {
            decayedSum(signals.qualityEvents, now, HALF_LIFE_POS_DAYS)
        } else {
            0.0
        }

        val base = if (signals.isPaid) {
            W_DIVERSITY * norm(diversityRaw, ANCHOR_DIVERSITY) +
                W_PAYING * norm(payingRaw, ANCHOR_PAYING) +
                W_DEMAND * norm(demandRaw, ANCHOR_DEMAND) +
                W_ACTIVITY * norm(activityRaw, ANCHOR_ACTIVITY)
        } else {
            W_DIVERSITY_FREE * norm(diversityRaw, ANCHOR_DIVERSITY) +
                W_DEMAND_FREE * norm(demandRaw, ANCHOR_DEMAND) +
                W_ACTIVITY_FREE * norm(activityRaw, ANCHOR_ACTIVITY)
        }

        val disputePenalty = (decayedSum(signals.disputes, now, HALF_LIFE_NEG_DAYS) * DISPUTE_W)
            .coerceAtMost(DISPUTE_CAP)
        val ghostPenalty = (decayedSum(signals.ghosting, now, HALF_LIFE_NEG_DAYS) * GHOST_W)
            .coerceAtMost(GHOST_CAP)
        val softPenalty = (
            decayedSum(signals.autoRejects, now, HALF_LIFE_NEG_DAYS) * SOFT_W +
                decayedSum(signals.skladchinaGhosts, now, HALF_LIFE_NEG_DAYS) * SOFT_W
            ).coerceAtMost(SOFT_CAP)

        val penalized = base - disputePenalty - ghostPenalty - softPenalty

        val anomalyMultiplier = anomalyMultiplier(signals)
        val tenureFactor = ((now.toEpochSecond() - signals.clubCreatedAt.toEpochSecond()) / 86_400.0 /
            TENURE_FULL_DAYS).coerceIn(0.0, 1.0)

        val rankScore = (penalized * anomalyMultiplier * tenureFactor *
            CO_OCCURRENCE_COLLAPSE * TRANSFER_PROBATION).coerceAtLeast(0.0)

        return ClubRank(signals.clubId, signals.ownerId, signals.category, rankScore, true, effectiveK)
    }

    /** Anomaly-множитель в [ANOMALY_FLOOR, 1.0]. v1 = один триггер («слишком чисто при объёме»). */
    private fun anomalyMultiplier(signals: ClubRankSignals): Double {
        var m = 1.0
        val tooCleanAtVolume = signals.core.size >= ANOMALY_CLEAN_MIN_CORE &&
            signals.disputes.isEmpty() && signals.ghosting.isEmpty() && signals.churnEvents90d == 0
        if (tooCleanAtVolume) m -= ANOMALY_STEP
        return m.coerceAtLeast(ANOMALY_FLOOR)
    }

    /**
     * Клубы, заслужившие «★ Топ-5 в категории», из всех ранжированных клубов прода. Применяет по
     * порядку: деплойный feature-флаг, kill-switch глобального пола ранга, per-owner collapse
     * (максимум 1 клуб на владельца в категории — убивает фабрикацию категорий), MIN_CATEGORY_SIZE,
     * позицию ≤5, абсолютный пол скора и отрыв селективности от шестого клуба. Возвращает clubId с бейджем.
     */
    fun topInCategory(ranked: List<RankedClub>, badgeEnabled: Boolean): Set<UUID> {
        if (!badgeEnabled) return emptySet()
        if (ranked.size < GLOBAL_RANK_FLOOR) return emptySet()

        val badged = mutableSetOf<UUID>()
        ranked.groupBy { it.category }.forEach { (_, clubsInCategory) ->
            // Per-owner collapse: оставляем только лучший клуб каждого владельца — один оператор не заселит категорию.
            val perOwnerBest = clubsInCategory
                .groupBy { it.ownerId }
                .map { (_, clubs) -> clubs.maxBy { it.rankScore } }
                .sortedByDescending { it.rankScore }

            if (perOwnerBest.size < MIN_CATEGORY_SIZE) return@forEach

            val sixthScore = perOwnerBest[5].rankScore // существует: size ≥ MIN_CATEGORY_SIZE ≥ 6
            perOwnerBest.take(5).forEach { club ->
                if (club.rankScore >= BADGE_SCORE_FLOOR && club.rankScore - sixthScore >= SELECTIVITY_EPS) {
                    badged += club.clubId
                }
            }
        }
        return badged
    }
}
