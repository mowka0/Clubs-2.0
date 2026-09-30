package com.clubs.subscription

import com.clubs.common.config.PlatformAdmins
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.security.AuthenticatedUser
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Кто и когда может дёрнуть служебное списание (platform-billing.md § 11). Два независимых
 * условия: флаг включён И Telegram id вызывающего есть в списке администраторов платформы
 * ([PlatformAdmins], env `PLATFORM_ADMIN_TELEGRAM_IDS`). Выключенный флаг отвечает 404 — маршрута
 * как бы нет; чужой id при включённом — 403. Владение клубом проверяется отдельно, аннотацией контроллера.
 */
@Component
class ManualChargeAccess(
    @Value("\${billing.manual-charge.enabled:false}") private val enabled: Boolean,
    private val admins: PlatformAdmins,
) {

    fun require(user: AuthenticatedUser) {
        if (!enabled) throw NotFoundException("Not found")
        if (user.telegramId !in admins) throw ForbiddenException("Manual charge is available to platform admins only")
    }
}
