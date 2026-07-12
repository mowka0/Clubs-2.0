package com.clubs.membership

import com.clubs.bot.NotificationService
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Смена роли участника клуба (co-organizers): владелец назначает/снимает со-организатора.
 * Авторизация вызывающего (владелец) декларативна на контроллере (@RequiresOrganizer) — со-орг
 * менять роли не может, даже демоутить себя. Здесь — бизнес-правила:
 *  - роль только member <-> co_organizer ("organizer" запрещён: передача владения вне скоупа);
 *  - нельзя менять роль себе и владельцу («нельзя разжаловать владельца»);
 *  - промоут — только active-участника (У-9: назначить со-оргом должника-frozen опасно — момент
 *    разморозки внезапно дал бы ему права); демоут — при любом живом статусе;
 *  - лимит со-оргов на клуб (У-3), идемпотентность (повторный промоут/демоут -> 200 no-op);
 *  - конкурентная смена роли -> 409 (rows-affected guard в репозитории).
 * DM назначенному/снятому — best-effort (У-6), сбой не роняет операцию.
 */
@Service
class MemberRoleService(
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val mapper: MembershipMapper
) {
    private val log = LoggerFactory.getLogger(MemberRoleService::class.java)

    @Transactional
    fun changeRole(clubId: UUID, targetUserId: UUID, callerId: UUID, requestedRole: String): MembershipDto {
        val newRole = parseRole(requestedRole)
        if (targetUserId == callerId) {
            throw ValidationException("Нельзя менять роль самому себе")
        }
        val membership = membershipRepository.findByUserAndClub(targetUserId, clubId)
            ?: throw NotFoundException("Участник не найден в этом клубе")
        if (membership.status == MembershipStatus.cancelled) {
            // Вышедший/кикнутый — не участник: роль живёт в строке membership и умерла вместе с ней.
            throw NotFoundException("Участник не найден в этом клубе")
        }
        if (membership.role == MembershipRole.organizer) {
            throw ValidationException("Нельзя разжаловать владельца клуба")
        }
        if (membership.role == newRole) {
            // Идемпотентно: повторный промоут уже-со-орга / демоут уже-участника — no-op.
            return mapper.toDto(membership)
        }

        if (newRole == MembershipRole.co_organizer) {
            if (membership.status != MembershipStatus.active) {
                throw ValidationException("Назначить со-организатором можно только активного участника")
            }
            // Anti-TOCTOU: count и UPDATE не атомарны, поэтому промоуты клуба сериализуются
            // транзакционным advisory-локом — конкурентный промоут ждёт коммита первого и видит
            // уже обновлённый счётчик (иначе два параллельных запроса пробили бы лимит).
            membershipRepository.lockRoleChanges(clubId)
            if (membershipRepository.countCoOrganizers(clubId) >= CO_ORGANIZER_LIMIT) {
                throw ValidationException("Лимит со-организаторов: не больше $CO_ORGANIZER_LIMIT на клуб")
            }
        }

        // Защищённый переход WHERE role = <ожидаемая> (как org-toggle складчины): 0 строк =
        // конкурентная смена роли между нашим чтением и UPDATE -> 409.
        val updated = membershipRepository.updateRole(membership.id, membership.role, newRole)
        if (updated == 0) {
            throw ConflictException("Роль участника изменилась — обновите экран")
        }
        log.info("Role changed: clubId={} targetUserId={} role={} by={}", clubId, targetUserId, newRole.literal, callerId)
        notifyRoleChanged(clubId, targetUserId, promoted = newRole == MembershipRole.co_organizer)
        return mapper.toDto(membership.copy(role = newRole))
    }

    private fun parseRole(raw: String): MembershipRole = when (raw) {
        MembershipRole.member.literal -> MembershipRole.member
        MembershipRole.co_organizer.literal -> MembershipRole.co_organizer
        // В т.ч. "organizer": передача владения — вне скоупа (club-leave PR-2).
        else -> throw ValidationException("Недопустимая роль: допустимы только member и co_organizer")
    }

    // DM о назначении/снятии роли — «на лучших усилиях» (У-6): сбой Telegram никогда не откатывает
    // смену роли. Промоут — с deep-link на клуб; демоут — нейтральный текст без кнопки.
    private fun notifyRoleChanged(clubId: UUID, targetUserId: UUID, promoted: Boolean) {
        try {
            val telegramId = userRepository.findById(targetUserId)?.telegramId ?: return
            val clubName = clubRepository.findById(clubId)?.name ?: "клуб"
            if (promoted) {
                notificationService.sendDirectMessageWithDeepLink(
                    telegramId,
                    "⭐ Вас назначили со-организатором клуба «$clubName». Теперь вам доступны заявки, события, складчины и управление участниками.",
                    webAppPath = "/clubs/$clubId",
                    buttonText = "Открыть клуб"
                )
            } else {
                notificationService.sendDirectMessage(telegramId, "Роль со-организатора в клубе «$clubName» снята.")
            }
        } catch (e: Exception) {
            log.warn(
                "Failed to DM role change (non-fatal): clubId={} targetUserId={} promoted={} error={}",
                clubId, targetUserId, promoted, e.message
            )
        }
    }

    companion object {
        // Лимит со-организаторов на клуб (У-3): офлайн-клубы — десятки людей, >5 делегатов — признак
        // деградации ролей («все — начальники»); заодно ограничивает веер DM/уведомлений. Не env (YAGNI).
        const val CO_ORGANIZER_LIMIT = 5
    }
}
