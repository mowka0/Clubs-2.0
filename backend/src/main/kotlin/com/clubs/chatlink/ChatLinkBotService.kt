package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.club.ClubRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Сторона БОТА в привязке чата: обработка событий Telegram, которые роутит [com.clubs.bot.ClubsBot].
 * Привязка (deep link ?startgroup=<club_id> → /start с payload в группе), health-мониторинг
 * my_chat_member, миграция группы в супергруппу, callback «Отвязать чат» из DM-петли подтверждения.
 */
@Service
class ChatLinkBotService(
    private val chatLinkRepository: ChatLinkRepository,
    private val clubRepository: ClubRepository,
    private val userRepository: UserRepository,
    private val chatLinkService: ChatLinkService,
    private val gateway: ChatTelegramGateway,
    @Value("\${telegram.bot-username}") private val botUsername: String
) {
    private val log = LoggerFactory.getLogger(ChatLinkBotService::class.java)

    /**
     * `/start <club_id>` в группе — попытка привязки. Гейт безопасности (решение PO):
     * отправитель `/start` (= человек, добавивший бота по deep link'у) обязан быть владельцем
     * клуба. Любой отказ — объясняющее сообщение в чат; выходит ли бот из группы, решает
     * [refuse]: живую привязку этого чата отказ разрушать не имеет права.
     */
    @Transactional
    fun handleGroupStart(chatId: Long, chatTitle: String?, fromTelegramId: Long, clubId: UUID) {
        // Строку чата читаем РОВНО один раз за вызов: два чтения под READ COMMITTED могли бы
        // разойтись, и решение «чат занят» разъехалось бы с решением «чью строку освобождаем».
        val existingForChat = chatLinkRepository.findByChatId(chatId)
        // Живая привязка = за строкой стоит видимый клуб. Строка удалённого клуба чат НЕ занимает:
        // иначе он оставался бы занят навсегда — клуб скрыт, `findById` его не видит, отвязать
        // из приложения нечем.
        val liveLinkOfChat = existingForChat?.takeIf { clubRepository.findById(it.clubId) != null }

        val club = clubRepository.findById(clubId)
        if (club == null || !club.isActive) {
            refuse(chatId, clubId, liveLinkOfChat, senderIsVerifiedOwner = false, text = "Клуб не найден. Откройте «Управление клубом» в приложении Clubs и нажмите «Привязать чат» ещё раз.")
            return
        }

        val sender = userRepository.findByTelegramId(fromTelegramId)
        if (sender?.id != club.ownerId) {
            // Имени клуба в тексте нет намеренно: отказ уходит ДО проверки прав, и подстановка
            // названия подтверждала бы существование приватного клуба обладателю его UUID.
            refuse(chatId, clubId, liveLinkOfChat, senderIsVerifiedOwner = false, text = "Привязать чат может только владелец клуба в приложении Clubs.")
            return
        }

        val existingForClub = chatLinkRepository.findByClubId(clubId)
        if (existingForClub != null && existingForClub.chatId == chatId) {
            // Повторное добавление в тот же чат (типовой случай — бота кикнули и вернули кнопкой
            // «Привязать бота заново»): идемпотентно освежаем права и при необходимости
            // пересоздаём invite-ссылку. Сообщение в чат — ТО ЖЕ, что при первой привязке
            // (реестр багов №3: «уже привязан» сбивал с толку, когда бот фактически отсутствовал).
            // DM-петля подтверждения не дублируется — гейт владельца уже пройден.
            val state = gateway.getBotChatState(chatId)
            if (state != null) {
                chatLinkRepository.updateBotState(
                    clubId = clubId,
                    botStatus = BotChatStatus.fromTelegramStatus(state.statusLiteral),
                    canPinMessages = state.canPinMessages,
                    canInviteUsers = state.canInviteUsers,
                    canRestrictMembers = state.canRestrictMembers,
                    canManageTags = state.canManageTags
                )
                ensureInviteLink(
                    link = existingForClub,
                    nowInChat = BotChatStatus.fromTelegramStatus(state.statusLiteral).isInChat,
                    nowCanInvite = state.canInviteUsers
                )
            }
            gateway.sendGroupMessage(chatId, linkedMessage(club.name))
            return
        }
        if (existingForClub != null) {
            refuse(chatId, clubId, liveLinkOfChat, senderIsVerifiedOwner = true, text = "У клуба «${club.name}» уже привязан другой чат. Сначала отвяжите его в «Управлении клубом».")
            return
        }
        if (liveLinkOfChat != null) {
            refuse(
                chatId, clubId, liveLinkOfChat, senderIsVerifiedOwner = true,
                text = "Этот чат уже привязан к другому клубу. Один чат — один клуб: сначала отвяжите его " +
                    "в том клубе, «Управление» → «Чат»."
            )
            return
        }
        // Перехват чата с осиротевшей строкой. Владения клубом здесь МАЛО: сироты живут ровно
        // там, где бот сидит админом, и перехват отдал бы новому клубу все его права (инвайты,
        // закрепы, мьюты, баны, теги). Без этой проверки рядовой участник легаси-группы завёл бы
        // свой клуб и увёл группу под себя, а настоящие админы отменить это не смогли бы —
        // отвязка владельческая. Гейт применяется ТОЛЬКО к перехвату: на обычной привязке
        // Telegram и так спрашивает права у того, кто добавляет бота, а лишняя проверка сломала
        // бы сценарий «бота добавили обычным участником».
        if (existingForChat != null) {
            if (!gateway.isChatAdmin(chatId, fromTelegramId)) {
                refuse(
                    chatId, clubId, liveLinkOfChat, senderIsVerifiedOwner = true,
                    text = "Этот чат раньше принадлежал другому клубу. Перепривязать его может только " +
                        "администратор чата в Telegram."
                )
                return
            }
            log.warn(
                "Releasing orphan chat link of a deleted club: staleClubId={} chatId={} newClubId={}",
                existingForChat.clubId, chatId, clubId
            )
            chatLinkService.releaseKeepingBotInChat(existingForChat)
        }

        // Права на момент привязки: если владелец пропустил шаг «сделать админом», бот останется
        // member'ом — фичи в UI покажутся как недоступные, refresh дообогатит после выдачи прав.
        val state = gateway.getBotChatState(chatId)
        val link = chatLinkRepository.insert(
            ChatLink(
                clubId = clubId,
                chatId = chatId,
                chatTitle = chatTitle,
                // Гейт выше гарантирует sender.id == club.ownerId — используем ownerId (non-null тип).
                linkedByUserId = club.ownerId,
                linkedAt = OffsetDateTime.now(),
                botStatus = state?.let { BotChatStatus.fromTelegramStatus(it.statusLiteral) } ?: BotChatStatus.MEMBER,
                canPinMessages = state?.canPinMessages ?: false,
                canInviteUsers = state?.canInviteUsers ?: false,
                canRestrictMembers = state?.canRestrictMembers ?: false,
                canManageTags = state?.canManageTags ?: false,
                doorEnabled = false,
                doorInviteLink = null,
                livePinEnabled = false,
                skladchinaStatusEnabled = false,
                strictModeEnabled = false,
                awardTagsEnabled = false
            )
        )
        log.info("Chat linked: clubId={} chatId={} byTelegramId={} botStatus={}", clubId, chatId, fromTelegramId, link.botStatus.literal)

        // Invite-ссылка создаётся сразу при привязке (реестр багов №4): по ней работает кнопка
        // «Чат клуба» у участников — не дожидаясь включения тумблера «Вход через заявки».
        ensureInviteLink(link, nowInChat = link.botStatus.isInChat, nowCanInvite = link.canInviteUsers)

        // Петля подтверждения (решение PO): фишинг-привязка мгновенно видна и обратима.
        gateway.sendGroupMessage(chatId, linkedMessage(club.name))
        // Приглашение сидящим в чате (фидбек PO 2026-07-08): чат мог существовать до клуба —
        // зовём его участников в клуб кнопкой-диплинком. Только при ПЕРВИЧНОЙ привязке:
        // повторный /start (возврат кикнутого бота) приглашение не дублирует — спам-бюджет.
        gateway.sendGroupMessageWithUrlButton(
            chatId = chatId,
            text = "👋 Теперь встречи, сборы и записи клуба «${club.name}» живут в приложении Clubs.\n" +
                "Если ты ещё не в клубе — вступай, чтобы участвовать:",
            buttonText = "Вступить в клуб",
            url = clubMiniAppUrl(clubId)
        )
        gateway.sendDmWithCallbackButton(
            telegramId = fromTelegramId,
            text = "Чат «${chatTitle ?: "без названия"}» привязан к вашему клубу «${club.name}». Это были вы?\n\nЕсли нет — отвяжите чат кнопкой ниже.",
            buttonText = "Отвязать чат",
            callbackData = "$UNLINK_CALLBACK_PREFIX$clubId"
        )
    }

    /**
     * my_chat_member: статус самого бота в чате изменился (кикнут / вернули / выдали или отняли
     * права). Привязку НЕ удаляем — фичи гаснут, а после возврата прав всё оживает (мокап 01-C).
     */
    @Transactional
    fun handleMyChatMember(chatId: Long, newStatusLiteral: String, canPinMessages: Boolean, canInviteUsers: Boolean, canRestrictMembers: Boolean) {
        val link = chatLinkRepository.findByChatId(chatId) ?: return
        val status = BotChatStatus.fromTelegramStatus(newStatusLiteral)
        // Право «Управление тегами» (Bot API 9.5) не приходит в объекте старой библиотеки —
        // дотягиваем raw-вызовом, пока бот в чате (событие редкое, вызов дешёвый).
        val canManageTags = status.isInChat && gateway.fetchCanManageTags(chatId)
        chatLinkRepository.updateBotState(link.clubId, status, canPinMessages, canInviteUsers, canRestrictMembers, canManageTags)
        log.info(
            "Bot chat state updated: clubId={} chatId={} status={} canPin={} canInvite={} canRestrict={} canManageTags={}",
            link.clubId, chatId, status.literal, canPinMessages, canInviteUsers, canRestrictMembers, canManageTags
        )
        ensureInviteLink(link, nowInChat = status.isInChat, nowCanInvite = canInviteUsers)
    }

    /**
     * Группа мигрировала в супергруппу — Telegram сменил chat_id, переносим привязку.
     * Все invite-ссылки старой группы при миграции умирают — сразу пересоздаём для нового
     * chat_id (реестр багов №2), иначе кнопка «Чат клуба» и DM останутся с мёртвой ссылкой.
     */
    @Transactional
    fun handleChatMigration(oldChatId: Long, newChatId: Long) {
        val link = chatLinkRepository.findByChatId(oldChatId) ?: return
        chatLinkRepository.updateChatId(oldChatId, newChatId)
        log.info("Chat id migrated (group→supergroup): clubId={} {} → {}", link.clubId, oldChatId, newChatId)
        if (link.doorInviteLink != null) {
            // Отзывать старую бессмысленно — старого чата больше нет.
            val fresh = gateway.createJoinRequestInviteLink(newChatId, DOOR_INVITE_LINK_NAME)
            if (fresh != null) {
                chatLinkRepository.updateInviteLink(link.clubId, fresh)
                log.info("Invite link recreated after migration: clubId={} chatId={}", link.clubId, newChatId)
            } else {
                log.warn("Invite link recreation after migration failed — stale link remains until refresh: clubId={}", link.clubId)
            }
        }
    }

    /**
     * Кнопка «Отвязать чат» из DM-петли подтверждения. Возвращает текст для answerCallbackQuery.
     * Гейт: жать может только текущий владелец клуба (DM могли переслать).
     */
    @Transactional
    fun handleUnlinkCallback(fromTelegramId: Long, clubId: UUID): String {
        val club = clubRepository.findById(clubId) ?: return "Клуб не найден"
        val caller = userRepository.findByTelegramId(fromTelegramId)
        if (caller?.id != club.ownerId) return "Отвязать чат может только владелец клуба"
        val link = chatLinkRepository.findByClubId(clubId) ?: return "Чат уже отвязан"
        chatLinkService.doUnlink(link)
        return "Чат отвязан от клуба «${club.name}»"
    }

    /**
     * Гарантирует живую invite-ссылку, когда бот может приглашать. Два случая (реестр №2 и №4):
     *  - ссылки ещё нет (привязали без права приглашать, право выдали позже) → создать;
     *  - бот был кикнут и вернулся → Telegram ОТОЗВАЛ все его ссылки, старая мертва → пересоздать.
     * Вызывается из my_chat_member И повторного /start — порядок этих апдейтов Telegram не
     * гарантирует; двойного пересоздания нет, потому что условие сравнивает с состоянием строки
     * ДО обновления, а первый сработавший его уже обновил.
     */
    private fun ensureInviteLink(link: ChatLink, nowInChat: Boolean, nowCanInvite: Boolean) {
        if (!nowInChat || !nowCanInvite) return
        // Ссылка живая, если существует и бот всё это время оставался в чате с правом приглашать.
        val linkStillValid = link.doorInviteLink != null && link.botStatus.isInChat && link.canInviteUsers
        if (linkStillValid) return

        // Старую отзываем best-effort (после кика она и так мертва) — не копим живые дубли.
        link.doorInviteLink?.let { gateway.revokeInviteLink(link.chatId, it) }
        val fresh = gateway.createJoinRequestInviteLink(link.chatId, DOOR_INVITE_LINK_NAME)
        if (fresh != null) {
            chatLinkRepository.updateInviteLink(link.clubId, fresh)
            log.info("Invite link (re)created: clubId={} chatId={}", link.clubId, link.chatId)
        } else {
            log.warn("Invite link creation failed — will retry on next state transition/refresh: clubId={} chatId={}", link.clubId, link.chatId)
        }
    }

    // Единый текст подтверждения в чат — и при первой привязке, и при повторной (после кика).
    private fun linkedMessage(clubName: String): String =
        "✅ Чат привязан к клубу «$clubName». Управление — в приложении Clubs, вкладка «Чат»."

    // Deep link Main Mini App на страницу клуба (DeepLinkHandler фронта парсит club_<uuid>).
    // url-кнопка, не WebApp: WebApp-кнопки в группах запрещены Telegram (рамка слайса 3).
    private fun clubMiniAppUrl(clubId: UUID): String =
        "https://t.me/$botUsername?startapp=club_$clubId"

    /**
     * Отказ в привязке. Два независимых решения:
     *
     * 1. **Уходить ли боту** — только если чат не обслуживает живую привязку. Иначе посторонний
     *    одним `/start` с чужим payload'ом выгонял бы бота и ломал работающую интеграцию
     *    клуба-хозяина чата (дверь, закрепы, теги, баны). Свободный чат бот покидает: сидеть в
     *    чужой группе без привязки незачем.
     * 2. **Писать ли в чат** — да, кроме одного случая: непроверенный отправитель в чате с живой
     *    привязкой. Уйти оттуда бот не может, значит без этого исключения любой участник группы
     *    гонял бы `/start` с произвольным UUID и превращал бота в спамера чужого чата.
     *    Верифицированному владельцу (`senderIsVerifiedOwner`) сообщение уходит всегда — иначе
     *    он не поймёт, почему привязка не проходит.
     */
    private fun refuse(chatId: Long, requestedClubId: UUID, liveLinkOfChat: ChatLink?, senderIsVerifiedOwner: Boolean, text: String) {
        val chatIsBusy = liveLinkOfChat != null
        if (senderIsVerifiedOwner || !chatIsBusy) gateway.sendGroupMessage(chatId, text)
        if (!chatIsBusy) gateway.leaveChat(chatId)
        log.warn(
            "Chat link refused: requestedClubId={} chatId={} verifiedOwner={} botLeftChat={} chatOccupiedByClubId={}",
            requestedClubId, chatId, senderIsVerifiedOwner, !chatIsBusy, liveLinkOfChat?.clubId
        )
    }

    companion object {
        /** Префикс callback_data кнопки «Отвязать чат» в DM-петле подтверждения (дальше — UUID клуба). */
        const val UNLINK_CALLBACK_PREFIX = "chatlink:unlink:"
    }
}
