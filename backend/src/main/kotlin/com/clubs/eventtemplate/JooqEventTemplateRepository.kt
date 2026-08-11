package com.clubs.eventtemplate

import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.EVENT_TEMPLATES
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqEventTemplateRepository(
    private val dsl: DSLContext,
    private val mapper: EventTemplateMapper
) : EventTemplateRepository {

    private companion object {
        // Имя клуба алиасится: без алиаса оно столкнулось бы с event_templates.name
        // (обе колонки называются "name"), и выборка читала бы не то поле.
        private val CLUB_NAME = CLUBS.NAME.`as`("club_name")
    }

    override fun findByClubId(clubId: UUID): List<EventTemplateWithClub> =
        selectWithClub(EVENT_TEMPLATES.CLUB_ID.eq(clubId))

    override fun findByClubIds(clubIds: Collection<UUID>): List<EventTemplateWithClub> {
        if (clubIds.isEmpty()) return emptyList()
        // Мягко удалённый клуб отфильтрован: его шаблоны применять некуда, а в кросс-клубовом
        // списке пикера они выглядели бы живыми пунктами.
        return selectWithClub(EVENT_TEMPLATES.CLUB_ID.`in`(clubIds).and(CLUBS.IS_ACTIVE.isTrue))
    }

    override fun findByIdAndClubId(id: UUID, clubId: UUID): EventTemplateWithClub? =
        selectWithClub(EVENT_TEMPLATES.ID.eq(id).and(EVENT_TEMPLATES.CLUB_ID.eq(clubId))).firstOrNull()

    override fun countByClubId(clubId: UUID): Int =
        dsl.fetchCount(EVENT_TEMPLATES, EVENT_TEMPLATES.CLUB_ID.eq(clubId))

    override fun findIdByClubAndName(clubId: UUID, name: String): UUID? =
        dsl.select(EVENT_TEMPLATES.ID)
            .from(EVENT_TEMPLATES)
            .where(EVENT_TEMPLATES.CLUB_ID.eq(clubId))
            // lower(name) зеркалит уникальный индекс uq_event_templates_club_name — сравнение
            // должно совпадать с ним, иначе дружелюбная проверка пропустит то, что упрётся в БД.
            .and(DSL.lower(EVENT_TEMPLATES.NAME).eq(name.lowercase()))
            .fetchOne(EVENT_TEMPLATES.ID)

    // Запись и чтение разделены намеренно: имя клуба живёт в соседней таблице, и дописывать его
    // в INSERT ... RETURNING нечем. Перечитывание тем же selectWithClub держит один-единственный
    // способ собрать EventTemplateWithClub — иначе DTO создания и DTO списка разъехались бы.
    override fun create(request: SaveEventTemplateRequest, clubId: UUID, createdBy: UUID): EventTemplateWithClub {
        val id = UUID.randomUUID()
        dsl.insertInto(EVENT_TEMPLATES)
            .set(EVENT_TEMPLATES.ID, id)
            .set(EVENT_TEMPLATES.CLUB_ID, clubId)
            .set(EVENT_TEMPLATES.CREATED_BY, createdBy)
            .set(contentOf(request))
            .execute()
        return findByIdAndClubId(id, clubId)!!
    }

    override fun update(id: UUID, clubId: UUID, request: SaveEventTemplateRequest): EventTemplateWithClub? {
        val updated = dsl.update(EVENT_TEMPLATES)
            .set(contentOf(request))
            .set(EVENT_TEMPLATES.UPDATED_AT, OffsetDateTime.now())
            .where(EVENT_TEMPLATES.ID.eq(id))
            .and(EVENT_TEMPLATES.CLUB_ID.eq(clubId))
            .execute()
        if (updated == 0) return null
        return findByIdAndClubId(id, clubId)
    }

    override fun delete(id: UUID, clubId: UUID): Int =
        dsl.deleteFrom(EVENT_TEMPLATES)
            .where(EVENT_TEMPLATES.ID.eq(id))
            .and(EVENT_TEMPLATES.CLUB_ID.eq(clubId))
            .execute()

    /**
     * Содержимое шаблона одной картой «колонка → значение»: и insert, и update пишут ОДИН И ТОТ ЖЕ
     * набор — это семантика PUT «полная замена» (null = очистить). Общий источник избавляет от
     * ситуации, когда новое поле шаблона добавили в create и забыли в update.
     */
    private fun contentOf(request: SaveEventTemplateRequest): Map<org.jooq.Field<*>, Any?> = mapOf(
        EVENT_TEMPLATES.NAME to request.name.trim(),
        EVENT_TEMPLATES.TITLE to request.title.trim(),
        EVENT_TEMPLATES.DESCRIPTION to request.description.normalized(),
        EVENT_TEMPLATES.LOCATION_TEXT to request.locationText.normalized(),
        EVENT_TEMPLATES.LOCATION_LAT to request.locationLat,
        EVENT_TEMPLATES.LOCATION_LON to request.locationLon,
        EVENT_TEMPLATES.LOCATION_HINT to request.locationHint.normalized(),
        EVENT_TEMPLATES.PARTICIPANT_LIMIT to request.participantLimit,
        EVENT_TEMPLATES.IS_OPEN_EVENT to request.isOpenEvent,
        EVENT_TEMPLATES.IS_URGENT_EVENT to request.isUrgentEvent,
        EVENT_TEMPLATES.STAGE2_LEAD_MINUTES to request.stage2LeadMinutes,
        EVENT_TEMPLATES.PHOTO_URL to request.photoUrl.normalized(),
        EVENT_TEMPLATES.DEFAULT_WEEKDAY to request.defaultWeekday,
        EVENT_TEMPLATES.DEFAULT_TIME to request.defaultTime
    )

    /** Пробельная строка = «поля нет»: та же нормализация, что у события в EventService.createEvent. */
    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** Шаблоны с именем клуба, отсортированные так же, как их показывает список выбора. */
    private fun selectWithClub(condition: Condition): List<EventTemplateWithClub> =
        dsl.select(EVENT_TEMPLATES.asterisk(), CLUB_NAME)
            .from(EVENT_TEMPLATES)
            .join(CLUBS).on(CLUBS.ID.eq(EVENT_TEMPLATES.CLUB_ID))
            .where(condition)
            .orderBy(CLUB_NAME.asc(), EVENT_TEMPLATES.NAME.asc())
            .fetch()
            .map(::toWithClub)

    private fun toWithClub(record: Record): EventTemplateWithClub = EventTemplateWithClub(
        template = mapper.recordToDomain(record.into(EVENT_TEMPLATES)),
        clubName = record.get(CLUB_NAME)!!
    )
}
