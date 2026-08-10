package com.clubs.geo

import com.clubs.generated.jooq.tables.references.CITIES
import com.clubs.generated.jooq.tables.references.CLUBS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

/** Центр города, вокруг которого сужается поиск подсказок. Наружу, в DTO, не отдаётся. */
data class CityCenter(
    val lat: Double,
    val lon: Double
)

/**
 * Координаты города клуба для рамки поиска заведений.
 *
 * Отдельный узкий репозиторий, а не поле в `ClubDto`: координаты нужны только серверу (клиенту
 * их не показываем и не отдаём), а тащить их через доменную модель клуба значило бы расширять
 * контракт ради одной внутренней настройки запроса в Яндекс.
 */
interface CityCenterRepository {
    /** Центр города клуба; null — клуба нет или город у него не указан (тогда ищем без рамки). */
    fun findByClubId(clubId: UUID): CityCenter?
}

@Repository
class JooqCityCenterRepository(private val dsl: DSLContext) : CityCenterRepository {

    override fun findByClubId(clubId: UUID): CityCenter? {
        // INNER JOIN намеренно: клуб без города (city_id IS NULL) строки не даст, и вызывающий
        // получит null — ровно тот же случай, что «клуба нет», и обрабатывается он одинаково.
        val row = dsl.select(CITIES.LATITUDE, CITIES.LONGITUDE)
            .from(CLUBS)
            .join(CITIES).on(CLUBS.CITY_ID.eq(CITIES.ID))
            .where(CLUBS.ID.eq(clubId))
            .fetchOne() ?: return null

        val lat = row[CITIES.LATITUDE] ?: return null
        val lon = row[CITIES.LONGITUDE] ?: return null
        return CityCenter(lat = lat.toDouble(), lon = lon.toDouble())
    }
}
