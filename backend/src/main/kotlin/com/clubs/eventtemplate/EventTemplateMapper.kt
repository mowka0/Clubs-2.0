package com.clubs.eventtemplate

import com.clubs.generated.jooq.tables.records.EventTemplatesRecord
import org.springframework.stereotype.Component

@Component
class EventTemplateMapper {

    fun recordToDomain(record: EventTemplatesRecord): EventTemplate = EventTemplate(
        id = record.id!!,
        clubId = record.clubId!!,
        name = record.name!!,
        title = record.title!!,
        description = record.description,
        locationText = record.locationText,
        locationLat = record.locationLat,
        locationLon = record.locationLon,
        locationHint = record.locationHint,
        participantLimit = record.participantLimit,
        isOpenEvent = record.isOpenEvent!!,
        isUrgentEvent = record.isUrgentEvent!!,
        stage2LeadMinutes = record.stage2LeadMinutes,
        photoUrl = record.photoUrl,
        defaultWeekday = record.defaultWeekday,
        defaultTime = record.defaultTime,
        createdBy = record.createdBy!!,
        createdAt = record.createdAt,
        updatedAt = record.updatedAt
    )

    /**
     * Содержимое сохранённого шаблона — левая часть сравнения «такой шаблон уже есть».
     * Имя-ярлык в [EventTemplateContent] намеренно не входит: одинаковыми считаются шаблоны
     * с одинаковыми ПАРАМЕТРАМИ, как бы они ни назывались.
     */
    fun toContent(template: EventTemplate): EventTemplateContent = with(template) {
        EventTemplateContent(
            title = title,
            description = description,
            locationText = locationText,
            locationLat = locationLat,
            locationLon = locationLon,
            locationHint = locationHint,
            participantLimit = participantLimit,
            isOpenEvent = isOpenEvent,
            isUrgentEvent = isUrgentEvent,
            stage2LeadMinutes = stage2LeadMinutes,
            photoUrl = photoUrl,
            defaultWeekday = defaultWeekday,
            defaultTime = defaultTime
        )
    }

    /** Содержимое входящего запроса. Ждёт УЖЕ нормализованный запрос (см. `normalized()`). */
    fun toContent(request: SaveEventTemplateRequest): EventTemplateContent = with(request) {
        EventTemplateContent(
            title = title,
            description = description,
            locationText = locationText,
            locationLat = locationLat,
            locationLon = locationLon,
            locationHint = locationHint,
            participantLimit = participantLimit,
            isOpenEvent = isOpenEvent,
            isUrgentEvent = isUrgentEvent,
            stage2LeadMinutes = stage2LeadMinutes,
            photoUrl = photoUrl,
            defaultWeekday = defaultWeekday,
            defaultTime = defaultTime
        )
    }

    fun toDto(withClub: EventTemplateWithClub): EventTemplateDto = with(withClub.template) {
        EventTemplateDto(
            id = id,
            clubId = clubId,
            clubName = withClub.clubName,
            name = name,
            title = title,
            description = description,
            locationText = locationText,
            locationLat = locationLat,
            locationLon = locationLon,
            locationHint = locationHint,
            participantLimit = participantLimit,
            isOpenEvent = isOpenEvent,
            isUrgentEvent = isUrgentEvent,
            stage2LeadMinutes = stage2LeadMinutes,
            photoUrl = photoUrl,
            defaultWeekday = defaultWeekday,
            defaultTime = defaultTime,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
