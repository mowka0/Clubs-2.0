package com.clubs.club

import com.clubs.common.dto.PageResponse
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.application.ApplicationRepository
import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.chatlink.ChatLinkRepository
import com.clubs.chatlink.ChatLinkService
import com.clubs.city.CityService
import com.clubs.event.EventRepository
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.interest.InterestService
import com.clubs.membership.MembershipRepository
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.subscription.SubscriptionService
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

// Максимум клубов на одного организатора
private const val MAX_CLUBS_PER_ORGANIZER = 10
// Длина генерируемого инвайт-кода (символов)
private const val INVITE_CODE_LENGTH = 16

// Потолок длины названия клуба (совпадает с VARCHAR(60) в схеме): название чата длиннее — режем
private const val CLUB_NAME_MAX_LENGTH = 60
// Имя клуба, когда Telegram не отдал название чата (у групп оно бывает пустым)
private const val DEFAULT_CHAT_CLUB_NAME = "Клуб из чата"
// Лимит участников для клуба из чата: ставим потолок (V81), т.к. размер чата заранее неизвестен —
// Bot API не отдаёт список участников группы, только счётчик
private const val CHAT_CLUB_MEMBER_LIMIT = 500

// "Принадлежит клубу" для видимости реквизитов СБП — участники, которым может понадобиться
// платить, + действующий владелец. expired (должник по продлению) — главный кандидат на оплату.
private val MEMBER_REQUISITE_STATUSES = setOf(
    MembershipStatus.active, MembershipStatus.frozen, MembershipStatus.expired
)

@Service
class ClubService(
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val clubRoleGuard: ClubRoleGuard,
    private val eventRepository: EventRepository,
    private val skladchinaRepository: SkladchinaRepository,
    private val applicationRepository: ApplicationRepository,
    private val subscriptionService: SubscriptionService,
    private val chatLinkRepository: ChatLinkRepository,
    // Витрина чата на странице клуба (getClub) читает привязку напрямую; освобождение чата при
    // удалении клуба идёт через сервис. Цикла нет: ChatLinkService зависит от ClubRepository,
    // а не от ClubService.
    private val chatLinkService: ChatLinkService,
    private val userRepository: UserRepository,
    // Резолвит cityId в город справочника: клиент присылает только id, имя города берётся отсюда.
    private val cityService: CityService,
    // Темы клуба живут в общем с профилем словаре (club-interests.md). Цикла нет:
    // InterestService зависит только от своего репозитория.
    private val interestService: InterestService,
    private val mapper: ClubMapper
) {

    private val log = LoggerFactory.getLogger(ClubService::class.java)

    fun getClubs(filters: ClubFilterParams): PageResponse<ClubListItemDto> {
        filters.category?.let { validateCategory(it) }
        filters.accessType?.let { validateAccessType(it) }
        if (filters.minPrice != null && filters.maxPrice != null && filters.minPrice > filters.maxPrice) {
            throw ValidationException("minPrice must not be greater than maxPrice")
        }
        return clubRepository.findAll(filters)
    }

    @Transactional
    fun createClub(request: CreateClubRequest, ownerId: UUID): ClubDetailDto {
        validateCategory(request.category)
        validateAccessType(request.accessType)
        // Город обязан быть из справочника — иначе клуб не найдётся в каталоге (city-dictionary.md).
        val city = cityService.requireCity(request.cityId)
        // Платный клуб обязан иметь реквизиты СБП, чтобы участники знали, как платить
        // (de-Stars honor-system).
        if (request.subscriptionPrice > 0 && request.paymentLink.isNullOrBlank()) {
            throw ValidationException("Для платного клуба укажите реквизиты для взноса (СБП)")
        }

        val count = clubRepository.countByOwnerId(ownerId)
        if (count >= MAX_CLUBS_PER_ORGANIZER) throw ConflictException("Maximum $MAX_CLUBS_PER_ORGANIZER clubs per organizer")

        // Пейволл по ёмкости плана: создание ПЛАТНОГО клуба сверх потолка плана организатора
        // требует подписки. Бросает 402 (PaymentRequiredException) с целью для апгрейда.
        // Бесплатные клубы (subscription_price == 0) никогда его не триггерят — они не
        // расходуют ёмкость (payment-v2.md §3).
        if (request.subscriptionPrice > 0) {
            subscriptionService.requirePaidClubCapacity(ownerId, clubRepository.countPaidByOwnerId(ownerId))
        }

        val inviteCode = if (request.accessType == "private") generateInviteCode() else null
        val club = clubRepository.create(request, ownerId, inviteCode, city)
        log.info("Club created: id={} name='{}' category={} accessType={} ownerId={}", club.id, club.name, request.category, request.accessType, ownerId)

        // Автосоздание organizer-членства для владельца.
        // Обёрнуто в тот же @Transactional scope, что и clubRepository.create() выше —
        // если этот INSERT упадёт, строка клуба откатится, что предотвращает появление
        // осиротевших клубов без organizer-членства.
        membershipRepository.createOrganizer(ownerId, club.id)

        // Темы необязательны: клуб без разметки создаётся штатно (трение в воронке создания
        // дороже идеальной разметки — решение PO). Лишние сверх лимита отбрасывает сервис.
        val interests = request.interests.orEmpty()
        if (interests.isNotEmpty()) interestService.replaceClubInterests(club.id, interests)

        // Перечитываем, чтобы ответ нёс актуальный счётчик участников (= 1, организатор только
        // что добавлен), а не голый результат create() (0). findById считает счётчик из `memberships`.
        return mapper.toDetailDto(
            clubRepository.findById(club.id) ?: club,
            includeRequisites = true,
            interests = interestService.getClubInterests(club.id)
        )
    }

    /**
     * Клуб из телеграм-чата (спринт 1.0, разворот на плагин к чату). Формы создания нет:
     * известно только название чата и тот, кто добавил бота — он и становится владельцем.
     *
     * Отличия от [createClub], помимо отсутствия формы:
     * - город не задан (`city_id IS NULL`) и спрашивается в приложении сразу после подключения;
     * - клуб бесплатный, поэтому пейволл ёмкости плана не вызывается вовсе;
     * - доступ `private` — клуб живёт при своём чате, в каталоге посторонним делать нечего.
     *
     * Лимит участников выставляется потолком: в чате может быть сколько угодно людей, а узнать
     * их число заранее нельзя — Bot API не отдаёт список участников группы.
     */
    @Transactional
    fun createClubFromChat(chatTitle: String?, ownerId: UUID): Club {
        val count = clubRepository.countByOwnerId(ownerId)
        if (count >= MAX_CLUBS_PER_ORGANIZER) throw ConflictException("Maximum $MAX_CLUBS_PER_ORGANIZER clubs per organizer")

        val name = chatTitle?.trim()?.takeIf { it.isNotEmpty() }?.take(CLUB_NAME_MAX_LENGTH)
            ?: DEFAULT_CHAT_CLUB_NAME
        // Приватному клубу код нужен всегда: без него владелец не сможет позвать людей ссылкой.
        val club = clubRepository.createFromChat(
            name = name,
            ownerId = ownerId,
            memberLimit = CHAT_CLUB_MEMBER_LIMIT,
            inviteCode = generateInviteCode()
        )
        log.info("Club created from chat: id={} name='{}' ownerId={}", club.id, club.name, ownerId)

        // Тот же транзакционный scope, что и вставка клуба: организатор обязан существовать,
        // иначе клуб останется без владельческого членства (см. createClub).
        membershipRepository.createOrganizer(ownerId, club.id)
        return club
    }

    fun getClubByInviteCode(code: String): ClubDetailDto {
        // Кодов у клуба два (V71): прямой (invite_link — «Скопировать ссылку») и заявочный
        // (apply_invite_code — приглашение из Telegram). По заявочному вход в клуб «по заявке»
        // идёт через одобрение организатора; в open/private одобрения не существует, поэтому
        // там обе ссылки ведут себя одинаково.
        val direct = clubRepository.findByInviteCode(code)
        val club = direct
            ?: clubRepository.findByApplyInviteCode(code)
            ?: throw NotFoundException("Invite link not found")
        val requiresApplication = direct == null && club.accessType == AccessType.closed
        // Имя владельца — для строки организатора на посадочной (club-invites, кадр D).
        val owner = userRepository.findById(club.ownerId)
        // Факт привязки чата и включённой «двери» публичен (как в getClub) — посадочная показывает
        // пилюлю «В чат» с подсказкой. Саму invite-ссылку сюда не отдаём: эндпоинт приглашения
        // не знает вызывающего, а значит и не может проверить его право на вход в чат.
        val chatLink = chatLinkRepository.findByClubId(club.id)
        val chatLinked = chatLink?.botStatus?.isInChat == true
        return mapper.toDetailDto(
            club,
            chatLinked = chatLinked,
            chatDoorEnabled = chatLinked && chatLink?.doorEnabled == true,
            ownerFirstName = owner?.firstName,
            ownerLastName = owner?.lastName,
            inviteRequiresApplication = requiresApplication,
            interests = interestService.getClubInterests(club.id)
        )
    }

    /**
     * Инвайт-код клуба с ленивой генерацией: private-клуб получает код при создании,
     * open/closed — при первом использовании кнопки «Пригласить» (club-invites).
     */
    @Transactional
    fun ensureInviteCode(clubId: UUID): String {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        club.inviteLink?.let { return it }
        val code = generateInviteCode()
        clubRepository.updateInviteCode(clubId, code)
        log.info("Invite code lazily generated: clubId={}", clubId)
        return code
    }

    /**
     * «Заявочный» инвайт-код (V71) с той же ленивой генерацией: нужен приглашениям из Telegram,
     * чтобы в клубе «по заявке» вход шёл через одобрение организатора. Отдельный код, а не флаг
     * в ссылке — иначе получатель обошёл бы одобрение, стерев флаг.
     */
    @Transactional
    fun ensureApplyInviteCode(clubId: UUID): String {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        club.applyInviteCode?.let { return it }
        val code = generateInviteCode()
        clubRepository.updateApplyInviteCode(clubId, code)
        log.info("Apply-invite code lazily generated: clubId={}", clubId)
        return code
    }

    @Transactional
    fun regenerateInviteLink(clubId: UUID, userId: UUID): ClubDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        // У-4 (co-organizers): отзыв инвайт-ссылки — операционное управление каналом набора,
        // доступно менеджеру клуба (владелец или активный со-орг), не только владельцу.
        clubRoleGuard.requireCapability(club, userId, ClubCapability.MANAGE_INVITE_LINK)
        val newCode = generateInviteCode()
        val updated = clubRepository.updateInviteCode(clubId, newCode) ?: throw NotFoundException("Club not found")
        log.info("Invite link regenerated: clubId={} userId={}", clubId, userId)
        // Вызвать сюда мог только менеджер (гейт выше) — новый код ему и возвращаем.
        return mapper.toDetailDto(
            updated,
            includeRequisites = true,
            includeInviteLink = true,
            interests = interestService.getClubInterests(clubId)
        )
    }

    fun getClub(id: UUID, callerId: UUID): ClubDetailDto {
        val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
        // Реквизиты СБП видны только участникам клуба (active/frozen) + владельцу — заявитель
        // в ожидании / посетитель не должен видеть, как платить (de-Stars: взнос = участник→
        // организатор, honor-system).
        val membership = membershipRepository.findByUserAndClub(callerId, id)
        val isMember = membership != null && membership.status in MEMBER_REQUISITE_STATUSES

        // Чат-интеграция (club-chat-link): факт привязки и включённой «двери» публичен (гость
        // видит чип «у клуба есть чат»), а сама invite-ссылка — только тем, у кого есть доступ:
        // active-участник, отменённый в оплаченном периоде, владелец. Frozen/expired — должники,
        // им в чат рано (least exposure, зеркалит гейт ChatDoorService.hasClubAccess).
        // Ссылка отдаётся БЕЗ условия «дверь включена» (реестр багов №4): кнопка «Чат клуба»
        // работает сразу после привязки — участников с доступом бот впускает всегда.
        val chatLink = chatLinkRepository.findByClubId(id)
        val chatLinked = chatLink?.botStatus?.isInChat == true
        val chatDoorEnabled = chatLinked && chatLink?.doorEnabled == true
        val hasChatAccess = club.ownerId == callerId ||
            (membership != null && (membership.status == MembershipStatus.active ||
                (membership.status == MembershipStatus.cancelled &&
                    membership.subscriptionExpiresAt?.isAfter(OffsetDateTime.now()) == true)))
        return mapper.toDetailDto(
            club,
            includeRequisites = isMember,
            chatLinked = chatLinked,
            chatDoorEnabled = chatDoorEnabled,
            chatInviteLink = if (chatLinked && hasChatAccess) chatLink?.doorInviteLink else null,
            // Прямой инвайт-код (вход мимо заявки) — только менеджеру: обычный участник не должен
            // иметь возможности раздать ссылку в обход одобрения (решение PO 2026-07-30).
            includeInviteLink = clubRoleGuard.hasCapability(club, callerId, ClubCapability.MANAGE_INVITE_LINK),
            interests = interestService.getClubInterests(id)
        )
    }

    @Transactional
    fun updateClub(id: UUID, request: UpdateClubRequest, userId: UUID): ClubDetailDto {
        val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
        // Настройки клуба доступны менеджеру (EDIT_CLUB_SETTINGS); удаление ниже — owner-only.
        clubRoleGuard.requireCapability(club, userId, ClubCapability.EDIT_CLUB_SETTINGS)

        // СБП-реквизиты — владельческое (co-organizers, PO №2): деньги взносов идут владельцу,
        // поэтому подмена paymentLink/paymentMethodNote со-оргом = увод взносов. Менеджерский PUT
        // разрешает остальные настройки; «пытается изменить» = поле прислано И нормализованное
        // значение (пустое -> null, конвенция репозитория) отличается от текущего — no-op сабмит
        // с тем же значением не блокируется (фронт и так шлёт поле только при изменении).
        val requisitesChanged =
            (request.paymentLink != null && request.paymentLink.ifBlank { null } != club.paymentLink) ||
            (request.paymentMethodNote != null && request.paymentMethodNote.ifBlank { null } != club.paymentMethodNote)
        if (requisitesChanged && club.ownerId != userId) {
            throw ForbiddenException("Реквизиты для взноса задаёт владелец клуба")
        }

        // Перевод БЕСПЛАТНОГО клуба в ПЛАТНЫЙ (0 -> >0) — владельческое (EDIT_PAYMENT_REQUISITES,
        // club-roles §11): со-орг мог флипнуть цену, если реквизиты уже сохранены, — закрываем.
        val goingPaid = request.subscriptionPrice != null && request.subscriptionPrice > 0 && club.subscriptionPrice == 0
        if (goingPaid && club.ownerId != userId) {
            throw ForbiddenException("Перевести клуб в платный может только владелец")
        }

        // Превращение в платный расходует ёмкость плана — тот же пейволл, что и при создании,
        // иначе редактирование обходило бы потолок (payment-v2.md §3.6). Биллинг якорится на
        // ВЛАДЕЛЬЦА (payer = владелец): ёмкость считаем по club.ownerId, а не по вызывающему.
        if (goingPaid) {
            subscriptionService.requirePaidClubCapacity(club.ownerId, clubRepository.countPaidByOwnerId(club.ownerId))
        }

        // Инвариант: платный клуб обязан сохранять реквизиты СБП. Вычисляем итоговое состояние
        // после апдейта — цена из запроса или текущее значение; ссылка сохраняется, если ключ
        // отсутствует (null), очищается на пустой строке (та же конвенция, что использует
        // репозиторий). Блокирует переход 0→платный без ссылки, очистку ссылки у платного
        // клуба, и редактирование легаси платного клуба, у которого её никогда не было.
        val resultingPrice = request.subscriptionPrice ?: club.subscriptionPrice
        val resultingLink = if (request.paymentLink == null) club.paymentLink else request.paymentLink.ifBlank { null }
        if (resultingPrice > 0 && resultingLink.isNullOrBlank()) {
            throw ValidationException("Для платного клуба укажите реквизиты для взноса (СБП)")
        }

        // Город меняется только на существующий из справочника; не прислали cityId — не трогаем.
        val city = request.cityId?.let { cityService.requireCity(it) }
        val updated = clubRepository.update(id, request, city) ?: throw NotFoundException("Club not found after update")

        // Темы — часть настроек клуба (гейт EDIT_CLUB_SETTINGS выше), отдельного права не заводим:
        // тема не про деньги и не про доступ. null = ключа нет в теле = не трогать; пустой список
        // = снять все темы — та же конвенция, что у остальных nullable-полей апдейта.
        request.interests?.let { interestService.replaceClubInterests(id, it) }

        log.info("Club updated: id={} userId={}", id, userId)
        return mapper.toDetailDto(
            updated,
            includeRequisites = true,
            interests = interestService.getClubInterests(id)
        )
    }

    @Transactional
    fun deleteClub(id: UUID, userId: UUID) {
        val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club owner can delete it")

        // Каскад: мягко удалённый клуб не должен оставлять после себя живую активность. Иначе
        // планировщики продолжают её обрабатывать (фантомные DM "отметьте явку", запоздалые
        // штрафы за просрочку), а его страницы деталей отдают 404 на теперь-скрытом клубе.
        // Отменяем нефинализированные события и активные складчины, удаляем заявки в
        // ожидании/одобренные — напрямую через репозитории, а НЕ через их Services, чтобы
        // каскад никогда не затрагивал репутацию (финализированные события сохраняют свой
        // леджер; участники складчины в ожидании освобождаются, а не штрафуются). Членства
        // не требуют действий: "мои клубы" уже фильтруют по clubs.is_active.
        // См. orphan-memberships-cleanup.md.
        val cancelledEvents = eventRepository.cancelActiveEventsByClub(id)
        val cancelledSkladchinas = skladchinaRepository.cancelActiveByClub(id)
        val deletedApplications = applicationRepository.deleteActiveByClub(id)

        // Чат освобождаем ПОЛНОСТЬЮ (та же логика, что у владельческой отвязки): снимаем закрепы,
        // мьюты, баны и теги, отзываем invite-ссылку, выводим бота, удаляем строку привязки. Без
        // этого чат навсегда числился бы за скрытым клубом — отвязать его из приложения нечем
        // (эндпоинты chat-link владельческие и ходят через findById, который скрытый клуб не
        // видит), а повторная привязка упиралась бы в «Этот чат уже привязан к другому клубу».
        // Порядок относительно каскадов выше значения не имеет (закрепы и статус-посты снимаются
        // по chat_id, а не по статусу событий и сборов); до softDelete — чтобы освобождение
        // работало на ещё видимом клубе.
        val chatUnlinked = chatLinkService.releaseOnClubDeleted(id)

        clubRepository.softDelete(id)
        log.info(
            "Club soft-deleted: id={} userId={} cancelledEvents={} cancelledSkladchinas={} deletedApplications={} chatUnlinked={}",
            id, userId, cancelledEvents, cancelledSkladchinas, deletedApplications, chatUnlinked
        )
    }

    private fun generateInviteCode(): String =
        UUID.randomUUID().toString().replace("-", "").take(INVITE_CODE_LENGTH)

    private fun validateCategory(category: String) {
        try {
            ClubCategory.valueOf(category)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid category: $category")
        }
    }

    private fun validateAccessType(accessType: String) {
        try {
            AccessType.valueOf(accessType)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid access type: $accessType")
        }
    }
}
