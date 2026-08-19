package com.clubs.chatlink

import com.clubs.common.security.AuthenticatedUser
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

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
    private val chatLinkService: ChatLinkService,
    private val intentStore: ChatLinkIntentStore
) {
    @GetMapping("/new-club-url")
    fun newClubUrl(): ResponseEntity<NewClubChatLinkDto> =
        ResponseEntity.ok(NewClubChatLinkDto(chatLinkService.newClubStartGroupUrl()))

    /**
     * «Я иду добавлять бота в группу» — фронт зовёт это перед самым уходом в Telegram.
     *
     * Зачем: о добавлении бота Telegram сообщает апдейтом `my_chat_member`, в котором payload
     * ссылки отсутствует, а команду `/start <payload>` клиент не отправляет, когда ссылка
     * просит права администратора. Намерение, отложенное здесь, и подсказывает боту, что
     * делать с чатом (см. [ChatLinkIntentStore]).
     *
     * `clubId = null` — клуба ещё нет, чат станет новым клубом.
     */
    @PostMapping("/intent")
    fun rememberIntent(
        @RequestBody(required = false) request: ChatLinkIntentRequest?,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<Void> {
        val clubId = request?.clubId
        val intent = if (clubId == null) {
            ChatLinkIntentStore.Intent.NewClub
        } else {
            // Привязать чат к клубу может только его владелец — проверяем до того, как
            // намерение ляжет в Redis, иначе чужой клуб забрал бы чат чужими руками.
            chatLinkService.requireOwnedClub(clubId, user.userId)
            // «Иду выдавать права» — не привязка: Telegram переприглашает бота, и без отдельного
            // намерения это событие выглядело бы как новое добавление (дубль DM владельцу).
            if (request?.grantRightsOnly == true) {
                ChatLinkIntentStore.Intent.GrantRights(clubId)
            } else {
                ChatLinkIntentStore.Intent.LinkExistingClub(clubId)
            }
        }
        intentStore.remember(user.telegramId, intent)
        return ResponseEntity.noContent().build()
    }
}

/** Deep link `t.me/<bot>?startgroup=new` — открывает выбор группы в Telegram. */
data class NewClubChatLinkDto(
    val startGroupUrl: String
)

/**
 * Тело `POST /api/chat-link/intent`. `clubId = null` — человек заводит клуб из чата.
 * `grantRightsOnly` — чат уже привязан к этому клубу, человек идёт только за правами бота.
 */
data class ChatLinkIntentRequest(
    val clubId: UUID? = null,
    val grantRightsOnly: Boolean = false
)
