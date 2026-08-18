package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.club.ClubService
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Сторона БОТА в привязке чата: обработка событий Telegram, которые роутит [com.clubs.bot.ClubsBot].
 * Привязка по факту добавления бота в группу (`my_chat_member` + намерение из
 * [ChatLinkIntentStore]), health-мониторинг того же апдейта, миграция группы в супергруппу,
 * callback «Отвязать чат» из DM-петли подтверждения. Ветка `/start <payload>` осталась
 * запасным путём — для ссылок без запроса прав и для тех, кто пишет команду руками.
 */
@Service
class ChatLinkBotService(
    private val chatLinkRepository: ChatLinkRepository,
    private val clubRepository: ClubRepository,
    private val clubService: ClubService,
    private val userRepository: UserRepository,
    private val chatLinkService: ChatLinkService,
    private val intentStore: ChatLinkIntentStore,
    private val gateway: ChatTelegramGateway,
    @Value("\${telegram.bot-username}") private val botUsername: String
) {
    private val log = LoggerFactory.getLogger(ChatLinkBotService::class.java)

    /**
     * Стереть из группы служебную команду `/start@bot`, которую кладёт туда клиент Telegram.
     *
     * Своей команды человек не писал — её отправляет приложение Telegram при добавлении бота,
     * и в чате она выглядит мусором. Удаление best-effort: без права «Удаление сообщений»
     * Telegram откажет, и команда останется висеть.
     */
    fun deleteServiceCommand(chatId: Long, messageId: Long) {
        gateway.deleteMessage(chatId, messageId)
    }

    /**
     * Бота добавили в группу (`my_chat_member`) — единственная штатная точка входа привязки.
     *
     * Что делать с чатом, подсказывает намерение, отложенное приложением перед уходом в Telegram
     * ([ChatLinkIntentStore]): «привязать к клубу X» или «завести новый клуб». Намерения нет —
     * бота добавили мимо приложения (из меню Telegram, по чужой ссылке); в чат-модели у такого
     * добавления один разумный смысл, и это «пусть чат станет клубом».
     *
     * В самом чате при этом не появляется ни одного сообщения — ни от человека, ни от бота
     * (решение PO 2026-08-17): подключение должно быть незаметным для участников группы, пока
     * владелец не представит клуб сам.
     */
    @Transactional
    fun handleBotAddedToChat(chatId: Long, chatTitle: String?, fromTelegramId: Long) {
        when (val intent = intentStore.consume(fromTelegramId)) {
            is ChatLinkIntentStore.Intent.GrantRights -> refreshRightsAfterGrant(chatId, intent.clubId)
            is ChatLinkIntentStore.Intent.LinkExistingClub ->
                handleGroupStart(chatId, chatTitle, fromTelegramId, intent.clubId)
            else -> handleGroupStartNewClub(chatId, chatTitle, fromTelegramId)
        }
    }

    /**
     * Человек выдал боту права на последнем шаге мастера. Telegram делает это переприглашением
     * — бот выходит и тут же входит обратно, — поэтому событие приходит как «бота добавили».
     * Привязка при этом не менялась: перечитываем права и молчим, иначе владелец получал бы
     * второе «чат привязан» на каждую выдачу прав (баг staging 2026-08-18).
     */
    private fun refreshRightsAfterGrant(chatId: Long, clubId: UUID) {
        val link = chatLinkRepository.findByClubId(clubId)
        if (link == null || link.chatId != chatId) {
            // Выбрали не ту группу — это уже не выдача прав, а попытка привязки другого чата.
            log.warn("Grant-rights intent hit a foreign chat: clubId={} intentChatId={} linkedChatId={}", clubId, chatId, link?.chatId)
            return
        }
        val state = gateway.getBotChatState(chatId) ?: return
        val status = BotChatStatus.fromTelegramStatus(state.statusLiteral)
        chatLinkRepository.updateBotState(
            clubId = clubId,
            botStatus = status,
            canPinMessages = state.canPinMessages,
            canInviteUsers = state.canInviteUsers,
            canRestrictMembers = state.canRestrictMembers,
            canManageTags = state.canManageTags
        )
        // Переприглашение убивает старые invite-ссылки группы — та же уборка, что при миграции.
        ensureInviteLink(link, nowInChat = status.isInChat, nowCanInvite = state.canInviteUsers)
        log.info(
            "Bot rights granted: clubId={} chatId={} status={} canPin={} canInvite={} canRestrict={} canManageTags={}",
            clubId, chatId, status.literal, state.canPinMessages, state.canInviteUsers, state.canRestrictMembers, state.canManageTags
        )
    }

    /**
     * Чат становится новым клубом: название берём у группы, владельцем становится тот, кто
     * добавил бота. Это точка входа чат-модели (спринт 1.0): человек не заполняет форму.
     *
     * Владельцем становится добавивший, даже если он не администратор группы (решение PO
     * 2026-08-17): инициативный участник имеет право попробовать бота и договориться с админом
     * — права боту тот выдаст потом, а до тех пор клуб живёт с приглушёнными функциями чата.
     *
     * Проверки конфликтов те же, что при обычной привязке, и по той же причине: занятый чат
     * новый клуб забирать не имеет права.
     */
    @Transactional
    fun handleGroupStartNewClub(chatId: Long, chatTitle: String?, fromTelegramId: Long) {
        val existingForChat = chatLinkRepository.findByChatId(chatId)
        val liveLinkOfChat = existingForChat?.takeIf { clubRepository.findById(it.clubId) != null }
        if (liveLinkOfChat != null) {
            // Чат уже принадлежит клубу ТОГО ЖЕ человека — это не конфликт, а повтор: следом за
            // добавлением бота Telegram кладёт в группу `/start`, и он приходит вторым событием
            // после `my_chat_member`, которое клуб уже создало. Отвечать на него нечего.
            val ownerOfLiveLink = clubRepository.findById(liveLinkOfChat.clubId)?.ownerId
            val sameOwner = ownerOfLiveLink != null && ownerOfLiveLink == userRepository.findByTelegramId(fromTelegramId)?.id
            if (sameOwner) {
                log.info("New-club link is a duplicate of just-created link, staying silent: chatId={} clubId={}", chatId, liveLinkOfChat.clubId)
                return
            }
            // Чужую живую привязку не трогаем и бота из чата не уводим — он там работает.
            gateway.sendDm(
                fromTelegramId,
                "Этот чат уже привязан к клубу. Один чат — один клуб: сначала отвяжите его " +
                    "в приложении Clubs, «Управление» → «Чат»."
            )
            log.warn("New-club link refused, chat busy: chatId={} byClubId={}", chatId, liveLinkOfChat.clubId)
            return
        }

        // Пользователь заводится только при входе в Mini App: человек мог добавить бота, ни разу
        // не открыв приложение, и тогда владельца клубу назначить не из чего. Написать ему тоже
        // нечем — Telegram запрещает боту первым писать незнакомцу, — поэтому просто уходим.
        val ownerId = userRepository.findByTelegramId(fromTelegramId)?.id
        if (ownerId == null) {
            gateway.leaveChat(chatId)
            log.warn("New-club link refused, sender unknown: chatId={} telegramId={}", chatId, fromTelegramId)
            return
        }

        // Осиротевшая строка удалённого клуба: перехват отдаёт новому клубу все права бота в этой
        // группе, поэтому — только администратору чата (та же защита, что при обычной привязке).
        if (existingForChat != null) {
            if (!gateway.isChatAdmin(chatId, fromTelegramId)) {
                gateway.sendDm(
                    fromTelegramId,
                    "Этот чат раньше принадлежал другому клубу. Подключить его заново может " +
                        "только администратор чата в Telegram."
                )
                log.warn("New-club orphan takeover refused, not chat admin: chatId={} telegramId={}", chatId, fromTelegramId)
                return
            }
            log.warn("Releasing orphan chat link for new club: staleClubId={} chatId={}", existingForChat.clubId, chatId)
            chatLinkService.releaseKeepingBotInChat(existingForChat)
        }

        // Размер клуба = размер чата: он должен вместить тех, кто уже в группе. Telegram может
        // не ответить — тогда сервис ставит запасной потолок, а человек правит в мастере.
        val club = clubService.createClubFromChat(chatTitle, ownerId, gateway.getChatMemberCount(chatId))
        log.info("Club created from chat: clubId={} chatId={} ownerTelegramId={}", club.id, chatId, fromTelegramId)
        // Ссылку в чат НЕ постим: клуб только что родился пустым, и приглашение смотреть на
        // страницу без описания и обложки потратило бы первое впечатление впустую. Презентует
        // орг сам, из шита «Пригласить» во вкладке «Участники» (решение PO 2026-08-17).
        linkChatToClub(chatId, chatTitle, fromTelegramId, club, announceInChat = false)
    }

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
            refuse(chatId, clubId, liveLinkOfChat, senderIsVerifiedOwner = false, fromTelegramId = fromTelegramId, text = "Клуб не найден. Откройте «Управление клубом» в приложении Clubs и нажмите «Привязать чат» ещё раз.")
            return
        }

        val sender = userRepository.findByTelegramId(fromTelegramId)
        if (sender?.id != club.ownerId) {
            // Имени клуба в тексте нет намеренно: отказ уходит ДО проверки прав, и подстановка
            // названия подтверждала бы существование приватного клуба обладателю его UUID.
            refuse(chatId, clubId, liveLinkOfChat, senderIsVerifiedOwner = false, fromTelegramId = fromTelegramId, text = "Привязать чат может только владелец клуба в приложении Clubs.")
            return
        }

        val existingForClub = chatLinkRepository.findByClubId(clubId)
        if (existingForClub != null && existingForClub.chatId == chatId) {
            // Повторное добавление в тот же чат (типовой случай — бота кикнули и вернули кнопкой
            // «Привязать бота заново»): идемпотентно освежаем права и при необходимости
            // пересоздаём invite-ссылку. Подтверждение — ТО ЖЕ, что при первой привязке
            // (реестр багов №3: «уже привязан» сбивал с толку, когда бот фактически отсутствовал),
            // и уходит владельцу в личку, а не в чат: участникам группы это сообщение не адресовано
            // (решение PO 2026-08-15). Закреп со ссылкой на клуб не переспамливаем — он уже висит.
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
            sendLinkedDm(
                fromTelegramId, chatTitle, club.name, clubId,
                botHasAdminRights = state?.statusLiteral?.let { BotChatStatus.fromTelegramStatus(it) } == BotChatStatus.ADMINISTRATOR
            )
            return
        }
        if (existingForClub != null) {
            refuse(chatId, clubId, liveLinkOfChat, senderIsVerifiedOwner = true, fromTelegramId = fromTelegramId, text = "У клуба «${club.name}» уже привязан другой чат. Сначала отвяжите его в «Управлении клубом».")
            return
        }
        if (liveLinkOfChat != null) {
            refuse(
                chatId, clubId, liveLinkOfChat, senderIsVerifiedOwner = true, fromTelegramId = fromTelegramId,
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
                    chatId, clubId, liveLinkOfChat, senderIsVerifiedOwner = true, fromTelegramId = fromTelegramId,
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

        linkChatToClub(chatId, chatTitle, fromTelegramId, club)
    }

    /**
     * Собственно привязка: строка `club_chat_links`, invite-ссылка, закреп в чате и DM владельцу.
     * Вызывается из двух мест — привязка к существующему клубу и создание клуба из чата
     * (`?startgroup=new`), — поэтому все проверки конфликтов остаются у вызывающего.
     */
    private fun linkChatToClub(
        chatId: Long,
        chatTitle: String?,
        fromTelegramId: Long,
        club: Club,
        announceInChat: Boolean = true,
    ) {
        val clubId = club.id
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

        // Единственное сообщение в чат: ссылка на клуб + зов вступить, закреплённое (решение PO
        // 2026-08-15 — раньше сюда прилетали три уведомления подряд). Постим ВСЕГДА, даже без
        // права закреплять: без закрепа сообщение просто остаётся в ленте, а чат не должен
        // оставаться вовсе без следа привязки. Подтверждение привязки уехало в личку владельцу.
        if (announceInChat) {
            chatLinkService.postAndPinClubLink(chatId, club.name, clubId)
                ?.let { chatLinkRepository.updateClubPinMessageId(clubId, it) }
        }
        // Слепок «видна ли новичкам история»: при скрытой истории закрепы для них не существуют,
        // и таб «Чат» покажет владельцу подсказку, как это переключить.
        gateway.getChatInfo(chatId)?.let {
            chatLinkRepository.updateHistoryVisibility(clubId, it.hasVisibleHistory)
        }
        // Личка владельцу — одно сообщение на две задачи: подтверждение привязки (раньше висело
        // отдельным постом В ЧАТЕ) и петля безопасности «это были вы?», из-за которой
        // фишинг-привязка мгновенно видна и обратима.
        sendLinkedDm(fromTelegramId, chatTitle, club.name, clubId, botHasAdminRights = link.botStatus == BotChatStatus.ADMINISTRATOR)
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

    /**
     * Единый текст подтверждения ВЛАДЕЛЬЦУ В ЛИЧКУ — и при первой привязке, и при повторной
     * (после кика). Совмещает две задачи: сказать, что привязка прошла, и оставить петлю
     * безопасности «это были вы?» с кнопкой отвязки. Раньше первая половина уходила отдельным
     * постом в чат, где была не к месту (решение PO 2026-08-15).
     */
    private fun linkedMessage(chatTitle: String?, clubName: String, botHasAdminRights: Boolean): String {
        val rights = if (botHasAdminRights) {
            ""
        } else {
            // Права выдаёт только администратор группы — сам себя бот повысить не может. Если
            // добавивший не админ, ему остаётся показать эту ссылку тому, кто админ; в приложении
            // тот же шаг стоит последним в мастере наполнения.
            "\n\n⚠️ Боту не выдали права администратора — опросы, закрепы и приглашения пока " +
                "не работают. Выдать их можно последним шагом в приложении: «Заполнить клуб»."
        }
        return "✅ Чат «${chatTitle ?: "без названия"}» привязан к клубу «$clubName».\n" +
            "Управление — в приложении Clubs, вкладка «Чат»." + rights + "\n\n" +
            "Это были вы? Если нет — отвяжите чат кнопкой ниже."
    }

    /**
     * Подтверждение привязки в личку владельцу: сверху вход в клуб, снизу петля безопасности
     * «это были вы?». Один текст на оба вызова — первую привязку и повторное добавление бота.
     */
    private fun sendLinkedDm(
        telegramId: Long,
        chatTitle: String?,
        clubName: String,
        clubId: UUID,
        botHasAdminRights: Boolean,
    ) {
        gateway.sendDmWithWebAppAndCallbackButton(
            telegramId = telegramId,
            text = linkedMessage(chatTitle, clubName, botHasAdminRights),
            webAppButtonText = "Перейти в клуб",
            webAppPath = "/clubs/$clubId",
            callbackButtonText = "Отвязать чат",
            callbackData = "$UNLINK_CALLBACK_PREFIX$clubId"
        )
    }

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
    private fun refuse(
        chatId: Long,
        requestedClubId: UUID,
        liveLinkOfChat: ChatLink?,
        senderIsVerifiedOwner: Boolean,
        text: String,
        fromTelegramId: Long,
    ) {
        val chatIsBusy = liveLinkOfChat != null
        // Отказ уходит в личку тому, кто добавлял бота, а не в группу: участники чата к этому
        // разговору отношения не имеют, а подключение обязано быть для них незаметным
        // (решение PO 2026-08-17). Условие senderIsVerifiedOwner сохранено: постороннему, чей
        // чат занят чужим клубом, не отвечаем вовсе — иначе перебором UUID можно было бы
        // выяснять, к какому клубу привязана группа.
        if (senderIsVerifiedOwner || !chatIsBusy) gateway.sendDm(fromTelegramId, text)
        if (!chatIsBusy) gateway.leaveChat(chatId)
        log.warn(
            "Chat link refused: requestedClubId={} chatId={} verifiedOwner={} botLeftChat={} chatOccupiedByClubId={}",
            requestedClubId, chatId, senderIsVerifiedOwner, !chatIsBusy, liveLinkOfChat?.clubId
        )
    }

    companion object {
        /** Префикс callback_data кнопки «Отвязать чат» в DM-петле подтверждения (дальше — UUID клуба). */
        const val UNLINK_CALLBACK_PREFIX = "chatlink:unlink:"

        /**
         * Payload ссылки `?startgroup=<payload>`, означающий «клуба ещё нет, создай его из этого
         * чата». Не UUID намеренно: ссылка одна на всех и живёт в рекламе, привязать её к
         * конкретному клубу заранее нельзя.
         */
        const val NEW_CLUB_START_PAYLOAD = "new"
    }
}
