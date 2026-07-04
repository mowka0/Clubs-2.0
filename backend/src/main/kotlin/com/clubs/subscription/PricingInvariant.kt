package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPlan

/**
 * Инвариант «бесплатный пол + скидка за объём без обрыва» (docs/modules/payment-v2.md §3.2/§3.4).
 *
 * Защищает ЗАФИКСИРОВАННОЕ продуктовое правило независимо от чисел на старте, чтобы будущая правка
 * цены никогда молча не вернула отвергнутую лестницу «наказать следующий клуб». Чистая функция →
 * напрямую юнит-тестируется; также годится для fail-fast проверки при старте против seed-строк
 * `subscription_pricing`.
 */
object PricingInvariant {

    fun violations(priceKopecksByPlan: Map<SubscriptionPlan, Int>): List<String> {
        val free = priceKopecksByPlan[SubscriptionPlan.FREE]
        val trio = priceKopecksByPlan[SubscriptionPlan.TRIO]
        val unlimited = priceKopecksByPlan[SubscriptionPlan.UNLIMITED]
        if (free == null || trio == null || unlimited == null) {
            return listOf("pricing missing for one or more plans: $priceKopecksByPlan")
        }
        val errors = mutableListOf<String>()
        // FREE — это раздаточный пол, защищает хоббийного владельца одного клуба / discovery-движок.
        if (free != 0) errors += "FREE must be 0 (giveaway floor), was $free"
        // Общая цена не убывает с ростом ёмкости.
        if (trio < free) errors += "TRIO ($trio) must be >= FREE ($free)"
        if (unlimited < trio) errors += "UNLIMITED ($unlimited) must be >= TRIO ($trio)"
        // No-cliff: UNLIMITED открывается дёшево, не более чем в 2× входного платного тарифа.
        if (unlimited > 2 * trio) errors += "UNLIMITED ($unlimited) must be <= 2×TRIO (${2 * trio}) [no-cliff]"
        return errors
    }

    fun isValid(priceKopecksByPlan: Map<SubscriptionPlan, Int>): Boolean =
        violations(priceKopecksByPlan).isEmpty()
}
