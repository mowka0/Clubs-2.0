package com.clubs.bot

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.adminrights.SetMyDefaultAdministratorRights
import org.telegram.telegrambots.meta.api.objects.adminrights.ChatAdministratorRights
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * Права, которые Telegram предлагает выдать боту при добавлении в группу.
 *
 * Ссылка привязки перечисляет их в `?admin=…`, но клиент разбирает этот список сам, и самое
 * новое право — «Управление тегами» (Bot API 9.5) — в галочках не появлялось: человеку
 * приходилось искать тумблер руками, а не знающий о нём уходил с полумёртвым ботом
 * (замечание PO 2026-08-19).
 *
 * Дефолтные права — второй, независимый от ссылки канал: Telegram запоминает их за ботом и
 * подставляет в экран добавления. Ставятся один раз при старте приложения, вызов идемпотентный.
 */
@Component
class BotDefaultRightsInitializer(
    private val telegramClient: TelegramClient
) {
    private val log = LoggerFactory.getLogger(BotDefaultRightsInitializer::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun publishDefaultRights() {
        val rights = ChatAdministratorRights.builder()
            // Наш рабочий набор: закреп статусов встреч, приглашения по заявке, снятие банов
            // (реестр багов №1), теги наград и чистка служебной команды `/start@bot`.
            .canPinMessages(true)
            .canInviteUsers(true)
            .canRestrictMembers(true)
            .canManageTags(true)
            .canDeleteMessages(true)
            // Обязательные поля объекта, которые нам не нужны, — явные false: билдер не проставит
            // их сам, а Telegram ждёт полный набор флагов.
            .canManageChat(false)
            .canChangeInfo(false)
            .canPromoteMembers(false)
            .canManageVideoChats(false)
            .canPostMessages(false)
            .canEditMessages(false)
            .canPostStories(false)
            .canEditStories(false)
            .canDeleteStories(false)
            .isAnonymous(false)
            .build()

        // Best-effort: без дефолтных прав продукт живёт как раньше (ссылка со своим `admin=`),
        // поэтому сбой Telegram не должен ронять старт приложения.
        try {
            telegramClient.execute(
                SetMyDefaultAdministratorRights.builder().rights(rights).forChannels(false).build()
            )
            log.info("Default admin rights published for groups: pin, invite, restrict, tags, delete")
        } catch (e: Exception) {
            log.warn("setMyDefaultAdministratorRights failed — link-only rights remain: {}", e.message)
        }
    }
}
