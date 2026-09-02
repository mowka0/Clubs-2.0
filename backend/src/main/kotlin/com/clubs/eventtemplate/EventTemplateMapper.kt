package com.clubs.eventtemplate

import com.clubs.event.EventFormat
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
        minParticipants = record.minParticipants,
        // Формат определяет только наличие мест — как у события.
        format = if (record.participantLimit == null) EventFormat.OPEN else EventFormat.NORMAL,
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
            minParticipants = minParticipants,
            format = format,
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
            minParticipants = effectiveMinParticipants,
            format = format.format,
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
            minParticipants = minParticipants,
            format = format,
            stage2LeadMinutes = stage2LeadMinutes,
            photoUrl = photoUrl,
            defaultWeekday = defaultWeekday,
            defaultTime = defaultTime,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
