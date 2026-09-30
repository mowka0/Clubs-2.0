package com.clubs.common.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Администраторы платформы — Telegram id из `platform.admin-telegram-ids`
 * (env `PLATFORM_ADMIN_TELEGRAM_IDS`, через запятую). Один список на два применения: допуск к
 * служебному списанию (`ManualChargeAccess`) и получатели недельного отчёта воронки
 * (`FunnelReportScheduler`). Пусто = никого. Нечисловой элемент пропускается с `warn` на старте:
 * для списания это fail-closed, а для отчёта — «никому», о чём иначе узнаёшь только в понедельник.
 */
@Component
class PlatformAdmins(@Value("\${platform.admin-telegram-ids:}") adminTelegramIds: String) {

    private val log = LoggerFactory.getLogger(PlatformAdmins::class.java)

    private val tokens: List<String> = adminTelegramIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val telegramIds: Set<Long> = tokens.mapNotNull { it.toLongOrNull() }.toSet()

    init {
        val rejected = tokens.count { it.toLongOrNull() == null }
        if (rejected > 0) log.warn("PLATFORM_ADMIN_TELEGRAM_IDS: {} of {} entries are not numeric and were ignored", rejected, tokens.size)
    }

    operator fun contains(telegramId: Long): Boolean = telegramId in telegramIds
}
