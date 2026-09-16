package com.clubs.subscription

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.security.AuthenticatedUser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Доступ к служебному списанию: флаг и список администраторов — два независимых замка. */
class ManualChargeAccessTest {

    private val admin = AuthenticatedUser(UUID.randomUUID(), telegramId = 111L)
    private val stranger = AuthenticatedUser(UUID.randomUUID(), telegramId = 222L)

    @Test
    fun `disabled flag hides the route even from admins`() {
        val access = ManualChargeAccess(enabled = false, adminTelegramIds = "111")
        assertThrows<NotFoundException> { access.require(admin) }
    }

    @Test
    fun `enabled flag lets listed admins through and refuses everyone else`() {
        val access = ManualChargeAccess(enabled = true, adminTelegramIds = " 111, 333 ")
        access.require(admin)
        assertThrows<ForbiddenException> { access.require(stranger) }
    }

    @Test
    fun `empty admin list refuses everyone even when enabled`() {
        val access = ManualChargeAccess(enabled = true, adminTelegramIds = "")
        assertThrows<ForbiddenException> { access.require(admin) }
    }
}
