package com.clubs.eventtemplate

import java.util.UUID

interface EventTemplateRepository {

    /** Шаблоны одного клуба, по имени (порядок списка выбора). */
    fun findByClubId(clubId: UUID): List<EventTemplateWithClub>

    /**
     * Шаблоны перечисленных клубов одним запросом — кросс-клубовый список в пикере «+».
     * Пустая коллекция → пустой результат без похода в БД.
     * Мягко удалённые клубы (`is_active = false`) исключаются: их шаблоны применять некуда.
     */
    fun findByClubIds(clubIds: Collection<UUID>): List<EventTemplateWithClub>

    /** Шаблон по id В ПРЕДЕЛАХ клуба — club-scoped пути не должны находить чужой шаблон. */
    fun findByIdAndClubId(id: UUID, clubId: UUID): EventTemplateWithClub?

    fun countByClubId(clubId: UUID): Int

    /**
     * ID шаблона клуба с таким именем без учёта регистра (null = имя свободно) — дружелюбная
     * проверка перед записью. Настоящий гарант — уникальный индекс uq_event_templates_club_name.
     */
    fun findIdByClubAndName(clubId: UUID, name: String): UUID?

    fun create(request: SaveEventTemplateRequest, clubId: UUID, createdBy: UUID): EventTemplateWithClub

    /** Полная замена содержимого; возвращает null, если шаблон не найден в этом клубе. */
    fun update(id: UUID, clubId: UUID, request: SaveEventTemplateRequest): EventTemplateWithClub?

    /** Возвращает число удалённых строк (0 = шаблона в этом клубе не было). */
    fun delete(id: UUID, clubId: UUID): Int
}
