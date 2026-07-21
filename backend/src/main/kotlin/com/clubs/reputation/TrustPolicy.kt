package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import java.time.OffsetDateTime
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * P1b Trust 0-100 — чистая формула. **Байесовская доля СДЕРЖАННЫХ обещаний с затуханием по свежести**,
 * классифицируемая ПО KIND (никогда по points: V18 бэкфиллил устаревшие величины, поэтому points
 * врут через границу V18; kept/broke-по-kind от величины не зависит).
 *
 * Дизайн + просимулированные контрактные числа: docs/modules/reputation-v2.md § P1b,
 * docs/backlog/p1b-trust-handoff.md, reputation-v2-redesign.md.
 *
 * Видимость: каждая константа здесь `internal` (§9.1) — пользователям никогда не показывается. UI
 * показывает только итоговое число 0-100 (за гейтом ReputationPolicy.isShown) и агрегат «N из M».
 *
 * Затухание считается ПРИ ЧТЕНИИ от occurred_at (оно меняется со временем → никогда не кэшируется).
 * Незатухающие счётчики kept/broke в user_club_reputation (V25) — дешёвый ориентир, а не это число.
 */
object TrustPolicy {

    // --- Trust на клуб (всё internal) ---
    /** Оптимистичный prior: пользователь без истории стартует с PRIOR (→ Trust 85), а не с 0/50. */
    const val PRIOR = 0.85
    /** Байесовская сила: ~K априорных исходов. Больше = снисходительнее к первым промахам. */
    const val K = 3.0
    /** Асимметрия: нарушенное обещание весит ASYM× от сдержанного (намерение↔действие). */
    const val ASYM = 2.0
    /** Штраф/кредит теряет половину веса через столько дней (клапан восстановления). */
    const val HALF_LIFE_DAYS = 90.0

    // --- глобальный агрегат «надёжен в N из M клубов» (всё internal) ---
    /** Per-club Trust не ниже этого порога засчитывает клуб в N (надёжен). */
    const val RELIABLE_THRESHOLD = 70
    /** Water-filling cap: один клуб даёт не больше этой доли глобального числа. */
    const val CLUB_WEIGHT_CAP = 0.5
    /** Фактор объёма outcome/(outcome+VOLUME_K): малое число исходов весит меньше в глобальном среднем. */
    const val VOLUME_K = 3.0
    /** Глобальная свежесть: вклад клуба уполовинивается после стольких дней неактивности. */
    const val GLOBAL_HALF_LIFE_DAYS = 365.0

    enum class TrustClass { KEPT, BROKE, NEUTRAL }

    fun classOf(kind: ReputationKind): TrustClass = when (kind) {
        ReputationKind.ironclad, ReputationKind.spontaneous, ReputationKind.skladchina_paid -> TrustClass.KEPT
        ReputationKind.no_show, ReputationKind.spectator, ReputationKind.skladchina_expired,
        ReputationKind.abandoned_slot,
        // open_no_show ЗАРЕЗЕРВИРОВАН и сейчас не выдаётся (открытые встречи вне репутации,
        // PO 2026-07-21); классификация BROKE — на случай будущего «строгого режима».
        ReputationKind.open_no_show -> TrustClass.BROKE
        // confirmed_unresolved (disputed/unmarked) и исторический skladchina_declined нейтральны:
        // исключаются из знаменателя — это ни сдержанное, ни нарушенное обещание.
        ReputationKind.confirmed_unresolved, ReputationKind.skladchina_declined -> TrustClass.NEUTRAL
    }

    /** Один репутационный исход из ledger. */
    data class Outcome(val kind: ReputationKind, val occurredAt: OffsetDateTime)

    private fun decay(occurredAt: OffsetDateTime, now: OffsetDateTime, halfLifeDays: Double): Double {
        val ageDays = (now.toEpochSecond() - occurredAt.toEpochSecond()) / 86_400.0
        // Строка с датой в будущем (рассинхрон часов) считается свежей, но никогда не даёт вес >1.
        return 0.5.pow((if (ageDays < 0.0) 0.0 else ageDays) / halfLifeDays)
    }

    /** Байесовское ядро, общее для Kotlin-пути и SQL-пути (который заранее суммирует веса). */
    fun trustFromWeights(keptWeight: Double, brokeWeight: Double): Int =
        (100.0 * (keptWeight + K * PRIOR) / (keptWeight + ASYM * brokeWeight + K)).roundToInt()

    /** Суммарные decay-веса сдержанных/нарушенных обещаний — общая база [perClubTrust] и проекции «пути назад». */
    data class TrustWeights(val kept: Double, val broke: Double)

    /** Веса kept/broke из сырых исходов (decay от occurred_at к [now]). */
    fun weightsOf(outcomes: List<Outcome>, now: OffsetDateTime): TrustWeights {
        var keptW = 0.0
        var brokeW = 0.0
        for (o in outcomes) when (classOf(o.kind)) {
            TrustClass.KEPT -> keptW += decay(o.occurredAt, now, HALF_LIFE_DAYS)
            TrustClass.BROKE -> brokeW += decay(o.occurredAt, now, HALF_LIFE_DAYS)
            TrustClass.NEUTRAL -> Unit
        }
        return TrustWeights(keptW, brokeW)
    }

    /** Per-club Trust 0-100 из сырых исходов. Всегда возвращает число; гейт показа
     *  (ReputationPolicy.isShown(outcomeCount)) решает, показывать ли его в UI. */
    fun perClubTrust(outcomes: List<Outcome>, now: OffsetDateTime): Int =
        weightsOf(outcomes, now).let { trustFromWeights(it.kept, it.broke) }

    // --- «Путь назад»: проекция восстановления (docs/modules/reputation-path-back.md) ---
    /** Сколько шагов проекции перебираем максимум; UI при этом значении пишет «9+». */
    const val PATH_BACK_MAX_STEPS = 9

    /**
     * Предполагаемый интервал между встречами клуба (дней) для проекции «пути назад». Клубный ритм
     * из стратегии — ~2 встречи в месяц. Нужен, чтобы проекция моделировала ВРЕМЯ: будущее посещение
     * случится не «сейчас», а через k×интервал, и к этому моменту старые исходы (включая штрафы)
     * успеют затухнуть по HALF_LIFE_DAYS. Без этого проекция у пользователя с большой историей
     * обещала «+1 балл за встречу» — формула инертна к одному свежему исходу, тогда как в реальности
     * восстановление идёт двумя механизмами сразу: новые посещения + затухание старых промахов.
     */
    const val PATH_BACK_MEETING_INTERVAL_DAYS = 14.0

    /**
     * Проекция «пути назад»: Trust после [steps] дополнительных ПОСЕЩЕНИЙ с учётом времени.
     * Модель: посещение j случается через j×[PATH_BACK_MEETING_INTERVAL_DAYS] дней; к моменту
     * шага [steps] исходные веса затухают на d^steps, а вес посещения j — на d^(steps−j), где
     * d = 0.5^(интервал/HALF_LIFE_DAYS). Та же decay-механика, что и в живой формуле — никакой
     * новой математики, только честный сдвиг точки наблюдения в будущее.
     */
    fun projectedTrust(weights: TrustWeights, steps: Int): Int {
        val d = 0.5.pow(PATH_BACK_MEETING_INTERVAL_DAYS / HALF_LIFE_DAYS)
        var kept = weights.kept
        var broke = weights.broke
        repeat(steps) {
            // Один интервал: всё накопленное затухает, затем добавляется свежее посещение (вес 1.0).
            kept = kept * d + 1.0
            broke *= d
        }
        return trustFromWeights(kept, broke)
    }

    /**
     * Сколько посещений нужно, чтобы вернуться в надёжную зону (Trust >= [RELIABLE_THRESHOLD]).
     * Capped [PATH_BACK_MAX_STEPS]: при большой просадке возвращаем cap, а не точный марафон —
     * UI показывает «9+» (спека: не обещать точным числом то, что далеко).
     */
    fun meetingsToReliable(weights: TrustWeights): Int {
        for (k in 1..PATH_BACK_MAX_STEPS) {
            if (projectedTrust(weights, k) >= RELIABLE_THRESHOLD) return k
        }
        return PATH_BACK_MAX_STEPS
    }

    /** Позиция пользователя в одном клубе, как её видит глобальный агрегат. */
    data class ClubStanding(val trust: Int, val outcomeCount: Int, val lastOccurredAt: OffsetDateTime)

    /** Глобальное представление. score равен null, если нигде нет истории (M == 0). */
    data class GlobalTrust(val reliableClubs: Int, val trackRecordClubs: Int, val score: Int?)

    /**
     * Глобальное «надёжен в N из M клубов» по ВСЕМ клубам с историей (включая покинутые).
     * M = клубы с outcomeCount >= minOutcomes; N = из них те, где Trust >= RELIABLE_THRESHOLD.
     * score = взвешенное по разнообразию/свежести среднее per-club Trust, нормализованный вес
     * каждого клуба ограничен CLUB_WEIGHT_CAP (water-filling), чтобы один клуб не мог захватить число.
     * null при M == 0.
     */
    fun global(
        standings: List<ClubStanding>,
        now: OffsetDateTime,
        minOutcomes: Int = ReputationPolicy.MIN_OUTCOMES_FOR_DISPLAY
    ): GlobalTrust {
        val tracked = standings.filter { it.outcomeCount >= minOutcomes }
        if (tracked.isEmpty()) return GlobalTrust(reliableClubs = 0, trackRecordClubs = 0, score = null)

        val n = tracked.count { it.trust >= RELIABLE_THRESHOLD }
        val rawWeights = tracked.map { s ->
            val volume = s.outcomeCount.toDouble() / (s.outcomeCount + VOLUME_K)
            volume * decay(s.lastOccurredAt, now, GLOBAL_HALF_LIFE_DAYS)
        }
        val weights = capNormalize(rawWeights, CLUB_WEIGHT_CAP)
        val score = tracked.indices.sumOf { weights[it] * tracked[it].trust }.roundToInt()
        return GlobalTrust(reliableClubs = n, trackRecordClubs = tracked.size, score = score)
    }

    /**
     * Нормализует веса так, чтобы сумма была 1 и ни один элемент не превышал [cap] (water-filling):
     * элементы сверх cap обрезаются до cap, а их избыток пропорционально перераспределяется на остальные,
     * повторяя до стабилизации. Единственный элемент принудительно становится 1.0 (перераспределять некуда).
     */
    internal fun capNormalize(weights: List<Double>, cap: Double): List<Double> {
        val total = weights.sum()
        if (weights.size == 1 || total <= 0.0) return List(weights.size) { 1.0 / weights.size }
        val w = weights.map { it / total }.toMutableList()
        repeat(weights.size) {
            val over = w.indices.filter { w[it] > cap + 1e-9 }
            if (over.isEmpty()) return w
            val excess = over.sumOf { w[it] - cap }
            over.forEach { w[it] = cap }
            val underSum = w.indices.filter { it !in over }.sumOf { w[it] }
            if (underSum <= 1e-12) return List(weights.size) { cap } // все обрезаны — распределяем поровну
            w.indices.filter { it !in over }.forEach { w[it] += excess * (w[it] / underSum) }
        }
        return w
    }
}
