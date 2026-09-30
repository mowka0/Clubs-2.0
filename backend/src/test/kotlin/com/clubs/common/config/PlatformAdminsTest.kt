package com.clubs.common.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Разбор PLATFORM_ADMIN_TELEGRAM_IDS: пробелы и дубли терпим, мусор пропускаем, пусто = никого. */
class PlatformAdminsTest {

    @Test
    fun `список через запятую с пробелами и дублями`() {
        val admins = PlatformAdmins(" 111, 333 ,111,")

        assertEquals(setOf(111L, 333L), admins.telegramIds)
        assertTrue(111L in admins)
        assertFalse(222L in admins)
    }

    @Test
    fun `нечисловой элемент пропускается, остальные работают`() {
        assertEquals(setOf(111L), PlatformAdmins("111, 12345a").telegramIds)
    }

    @Test
    fun `пустая строка - никого`() {
        assertTrue(PlatformAdmins("").telegramIds.isEmpty())
        assertFalse(111L in PlatformAdmins(""))
    }
}
