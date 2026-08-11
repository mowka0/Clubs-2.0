package com.clubs.eventtemplate

import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class EventTemplateService(
    private val templateRepository: EventTemplateRepository,
    private val membershipRepository: MembershipRepository,
    private val clubRoleGuard: ClubRoleGuard,
    private val mapper: EventTemplateMapper
) {

    companion object {
        // Сколько шаблонов клуб может держать одновременно. Ограничение продуктовое, а не
        // техническое: список живёт в модальном шите, и больше десятка пунктов там уже не выбор,
        // а прокрутка. Держим в сервисе, а не в CHECK: DDL потребовал бы триггера ради числа,
        // которое почти наверняка будут крутить.
        const val MAX_TEMPLATES_PER_CLUB = 10
    }

    private val log = LoggerFactory.getLogger(EventTemplateService::class.java)

    /** Шаблоны одного клуба. Капабилити-гейт стоит аннотацией на контроллере. */
    fun getClubTemplates(clubId: UUID): List<EventTemplateDto> =
        templateRepository.findByClubId(clubId).map(mapper::toDto)

    /**
     * Шаблоны ВСЕХ клубов, где у вызывающего есть [ClubCapability.MANAGE_EVENTS] — питает список
     * в пикере «+», который кросс-клубовый. Аннотацией не гейтится (клуба в пути нет), поэтому
     * фильтрация клубов — здесь: она и есть авторизация этого эндпоинта.
     *
     * Владелец клуба проходит по своей же строке членства (role = organizer), отдельная ветка
     * owner-bypass не нужна: строка создаётся вместе с клубом.
     */
    fun getMyTemplates(userId: UUID): List<EventTemplateDto> {
        val manageableClubIds = membershipRepository.findByUserId(userId)
            .filter { clubRoleGuard.hasCapabilityInMembership(it, ClubCapability.MANAGE_EVENTS) }
            .map { it.clubId }
        return templateRepository.findByClubIds(manageableClubIds).map(mapper::toDto)
    }

    @Transactional
    fun createTemplate(clubId: UUID, request: SaveEventTemplateRequest, userId: UUID): EventTemplateDto {
        val count = templateRepository.countByClubId(clubId)
        if (count >= MAX_TEMPLATES_PER_CLUB) {
            throw ConflictException(
                "У клуба уже $MAX_TEMPLATES_PER_CLUB шаблонов — удалите ненужный, чтобы добавить новый"
            )
        }
        requireNameFree(clubId, request.name, exceptId = null)

        val created = runCatchingDuplicateName(request.name) {
            templateRepository.create(request, clubId, userId)
        }
        log.info(
            "Event template created: id={} clubId={} name='{}' userId={}",
            created.template.id, clubId, created.template.name, userId
        )
        return mapper.toDto(created)
    }

    @Transactional
    fun updateTemplate(
        clubId: UUID,
        templateId: UUID,
        request: SaveEventTemplateRequest,
        userId: UUID
    ): EventTemplateDto {
        // Существование проверяем В ПРЕДЕЛАХ клуба: шаблон чужого клуба должен выглядеть
        // несуществующим, а не «запрещённым» — иначе по коду ответа можно перебирать чужие id.
        templateRepository.findByIdAndClubId(templateId, clubId)
            ?: throw NotFoundException("Event template not found")
        requireNameFree(clubId, request.name, exceptId = templateId)

        val updated = runCatchingDuplicateName(request.name) {
            templateRepository.update(templateId, clubId, request)
                ?: throw NotFoundException("Event template not found")
        }
        log.info(
            "Event template updated: id={} clubId={} name='{}' userId={}",
            updated.template.id, clubId, updated.template.name, userId
        )
        return mapper.toDto(updated)
    }

    @Transactional
    fun deleteTemplate(clubId: UUID, templateId: UUID, userId: UUID) {
        val removed = templateRepository.delete(templateId, clubId)
        if (removed == 0) throw NotFoundException("Event template not found")
        log.info("Event template deleted: id={} clubId={} userId={}", templateId, clubId, userId)
    }

    /** Дружелюбная проверка занятости имени; настоящий гарант — уникальный индекс в БД. */
    private fun requireNameFree(clubId: UUID, name: String, exceptId: UUID?) {
        val existingId = templateRepository.findIdByClubAndName(clubId, name.trim())
        if (existingId != null && existingId != exceptId) {
            throw ConflictException("Шаблон с именем «${name.trim()}» в этом клубе уже есть")
        }
    }

    /**
     * Гонка двух одновременных сохранений проходит мимо [requireNameFree] и упирается в уникальный
     * индекс. Переводим её в тот же 409 с тем же текстом, чтобы пользователь не получил 500 на
     * ситуацию, у которой есть понятное объяснение.
     */
    private fun <T> runCatchingDuplicateName(name: String, block: () -> T): T =
        try {
            block()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("Шаблон с именем «${name.trim()}» в этом клубе уже есть")
        }
}
