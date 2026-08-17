package com.clubs.chatlink

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Ссылка «подключить чат» для человека, у которого клуба ещё нет.
 *
 * Отдельный контроллер, а не метод в [ChatLinkController]: тот весь висит на
 * `/api/clubs/{clubId}/chat-link` и требует клуб, которого здесь по условию не существует.
 *
 * Ссылку строит сервер, потому что username бота живёт в его окружении: продублировать его
 * во фронтовый конфиг значило бы завести второй источник правды, который однажды разъедется
 * со staging или prod.
 */
@RestController
@RequestMapping("/api/chat-link")
class NewClubChatLinkController(
    private val chatLinkService: ChatLinkService
) {
    @GetMapping("/new-club-url")
    fun newClubUrl(): ResponseEntity<NewClubChatLinkDto> =
        ResponseEntity.ok(NewClubChatLinkDto(chatLinkService.newClubStartGroupUrl()))
}

/** Deep link `t.me/<bot>?startgroup=new` — открывает выбор группы в Telegram. */
data class NewClubChatLinkDto(
    val startGroupUrl: String
)
