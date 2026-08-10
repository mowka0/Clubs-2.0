package com.clubs.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Чистые правила напоминаний об истечении подписки: какие пороги наступили, что уже отправлено
 * и как назван срок в тексте DM (docs/modules/membership-lifecycle.md §7).
 */
class ExpiryReminderRulesTest {

    // 12:00 МСК — как дневной тик крона в проде.
    private val now = OffsetDateTime.parse("2026-04-24T09:00:00Z")

    @Test
    fun `days left are counted in Moscow calendar days`() {
        // 23:00 МСК того же дня — сегодня, несмотря на 20:00 UTC.
        assertThat(ExpiryReminderRules.daysLeft(now, OffsetDateTime.parse("2026-04-24T20:00:00Z"))).isEqualTo(0)
        // 00:30 МСК следующих суток — это уже завтра, хотя по UTC ещё сегодня (21:30).
        assertThat(ExpiryReminderRules.daysLeft(now, OffsetDateTime.parse("2026-04-24T21:30:00Z"))).isEqualTo(1)
        assertThat(ExpiryReminderRules.daysLeft(now, OffsetDateTime.parse("2026-04-27T11:00:00Z"))).isEqualTo(3)
    }

    @Test
    fun `threshold is the nearest reached one`() {
        assertThat(ExpiryReminderRules.thresholdFor(4)).isNull()
        assertThat(ExpiryReminderRules.thresholdFor(3)).isEqualTo(3)
        assertThat(ExpiryReminderRules.thresholdFor(2)).isEqualTo(3)
        assertThat(ExpiryReminderRules.thresholdFor(1)).isEqualTo(1)
        assertThat(ExpiryReminderRules.thresholdFor(0)).isEqualTo(1)
    }

    @Test
    fun `a sent threshold closes itself and every later tick of it`() {
        assertThat(ExpiryReminderRules.isDue(threshold = 3, lastSentThreshold = null)).isTrue()
        assertThat(ExpiryReminderRules.isDue(threshold = 3, lastSentThreshold = 3)).isFalse()
        assertThat(ExpiryReminderRules.isDue(threshold = 1, lastSentThreshold = 3)).isTrue()
        assertThat(ExpiryReminderRules.isDue(threshold = 1, lastSentThreshold = 1)).isFalse()
    }

    @Test
    fun `deadline phrase follows the actual remainder`() {
        assertThat(ExpiryReminderRules.deadlinePhrase(3)).isEqualTo("через 3 дня")
        assertThat(ExpiryReminderRules.deadlinePhrase(2)).isEqualTo("через 2 дня")
        assertThat(ExpiryReminderRules.deadlinePhrase(1)).isEqualTo("завтра")
        assertThat(ExpiryReminderRules.deadlinePhrase(0)).isEqualTo("сегодня")
    }
}
