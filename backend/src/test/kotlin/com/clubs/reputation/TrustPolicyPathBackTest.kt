package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * «Путь назад» (reputation-path-back.md AC-1): проекция восстановления — детерминированная
 * подстановка в то же байесовское ядро TrustPolicy. Чистые юнит-тесты, фиксированное NOW.
 */
class TrustPolicyPathBackTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-07-05T12:00:00Z")

    private fun out(kind: ReputationKind, ageDays: Long = 0): TrustPolicy.Outcome =
        TrustPolicy.Outcome(kind, now.minusDays(ageDays))

    private val KEPT = ReputationKind.ironclad
    private val BROKE = ReputationKind.no_show
    private val NEUTRAL = ReputationKind.confirmed_unresolved

    @Test
    fun `weightsOf sums fresh outcomes at full weight and ignores neutral`() {
        val w = TrustPolicy.weightsOf(listOf(out(KEPT), out(KEPT), out(BROKE), out(NEUTRAL)), now)
        assertEquals(2.0, w.kept, 1e-9)
        // Нарушенное обещание весит |очки|/100 (V85): у неявки (−200) это 2.0, а не 1.0.
        assertEquals(2.0, w.broke, 1e-9)
    }

    @Test
    fun `perClubTrust equals trustFromWeights over weightsOf (single formula, two entry points)`() {
        val outcomes = listOf(out(KEPT, 10), out(BROKE, 40), out(KEPT, 95))
        val w = TrustPolicy.weightsOf(outcomes, now)
        assertEquals(
            TrustPolicy.trustFromWeights(w.kept, w.broke),
            TrustPolicy.perClubTrust(outcomes, now)
        )
    }

    @Test
    fun `projection climbs step by step - fresh 1 kept + 1 no-show = 44, reliable on 5th`() {
        // kept=1, broke=2.0 (неявка весит |−200|/100): (1+2.55)/(1+4+3) = 44. Проекция моделирует
        // время (встреча = +14 дней, d = 0.5^(14/90) ≈ 0.898): старые веса затухают, новое
        // посещение добавляется с весом 1.0. До надёжной зоны (≥70) теперь пять встреч, а не две —
        // прямое следствие того, что молчаливая неявка стала самым дорогим промахом.
        val w = TrustPolicy.weightsOf(listOf(out(KEPT), out(BROKE)), now)
        assertEquals(44, TrustPolicy.trustFromWeights(w.kept, w.broke))
        assertEquals(52, TrustPolicy.projectedTrust(w, 1))
        assertEquals(59, TrustPolicy.projectedTrust(w, 2))
        assertEquals(5, TrustPolicy.meetingsToReliable(w))
    }

    @Test
    fun `projection is monotonic - each extra visit never lowers trust`() {
        val w = TrustPolicy.weightsOf(listOf(out(BROKE), out(BROKE), out(KEPT)), now)
        var prev = TrustPolicy.trustFromWeights(w.kept, w.broke)
        for (k in 1..TrustPolicy.PATH_BACK_MAX_STEPS) {
            val next = TrustPolicy.projectedTrust(w, k)
            assertTrue(next >= prev, "projection dipped at step $k: $prev -> $next")
            prev = next
        }
    }

    @Test
    fun `deep hole is capped at PATH_BACK_MAX_STEPS, not promised as an exact marathon`() {
        val w = TrustPolicy.weightsOf(List(10) { out(BROKE) }, now)
        assertEquals(TrustPolicy.PATH_BACK_MAX_STEPS, TrustPolicy.meetingsToReliable(w))
        // Честность cap'а: даже после max шагов надёжная зона ещё не достигнута.
        assertTrue(TrustPolicy.projectedTrust(w, TrustPolicy.PATH_BACK_MAX_STEPS) < TrustPolicy.RELIABLE_THRESHOLD)
    }

    @Test
    fun `already reliable needs one step by definition of the loop floor`() {
        // trust уже >= 70 → показ «пути назад» отсекает DTO-маппер; сама функция честно вернёт 1.
        val w = TrustPolicy.weightsOf(listOf(out(KEPT), out(KEPT), out(KEPT)), now)
        assertTrue(TrustPolicy.trustFromWeights(w.kept, w.broke) >= TrustPolicy.RELIABLE_THRESHOLD)
        assertEquals(1, TrustPolicy.meetingsToReliable(w))
    }
}
