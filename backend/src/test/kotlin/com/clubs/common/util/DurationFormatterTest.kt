package com.clubs.common.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DurationFormatterTest {

    @Test
    fun `whole hours drop the minutes part`() {
        assertEquals("4 ч", DurationFormatter.formatMinutes(240))
    }

    @Test
    fun `exactly one hour`() {
        assertEquals("1 ч", DurationFormatter.formatMinutes(60))
    }

    @Test
    fun `hours and minutes`() {
        assertEquals("1 ч 30 мин", DurationFormatter.formatMinutes(90))
    }

    @Test
    fun `minutes only`() {
        assertEquals("45 мин", DurationFormatter.formatMinutes(45))
    }

    @Test
    fun `single minute — regression for the '0 ч' bug`() {
        assertEquals("1 мин", DurationFormatter.formatMinutes(1))
    }

    @Test
    fun `zero minutes`() {
        assertEquals("0 мин", DurationFormatter.formatMinutes(0))
    }
}
