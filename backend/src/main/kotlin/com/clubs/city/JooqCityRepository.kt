package com.clubs.city

import com.clubs.generated.jooq.tables.references.CITIES
import com.clubs.generated.jooq.tables.references.CLUBS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JooqCityRepository(private val dsl: DSLContext) : CityRepository {

    override fun findAll(): List<CityDto> {
        // Признак «в городе есть клубы» считаем через EXISTS, а не JOIN'ом с GROUP BY: справочник
        // отдаётся целиком, и группировка по 540 строкам ничего не даёт.
        val hasClubs = DSL.field(
            DSL.exists(
                DSL.selectOne()
                    .from(CLUBS)
                    .where(CLUBS.CITY_ID.eq(CITIES.ID).and(CLUBS.IS_ACTIVE.eq(true)))
            )
        ).`as`("has_clubs")

        return dsl.select(
            CITIES.ID, CITIES.NAME, CITIES.REGION, CITIES.NEEDS_REGION,
            CITIES.COUNTRY_CODE, CITIES.IS_FEATURED, hasClubs
        )
            .from(CITIES)
            .orderBy(CITIES.COUNTRY_CODE.asc(), CITIES.POPULATION.desc())
            .fetch { r ->
                CityDto(
                    id = r[CITIES.ID]!!,
                    name = r[CITIES.NAME]!!,
                    region = r[CITIES.REGION],
                    needsRegion = r[CITIES.NEEDS_REGION] ?: false,
                    countryCode = r[CITIES.COUNTRY_CODE]!!,
                    isFeatured = r[CITIES.IS_FEATURED] ?: false,
                    hasClubs = r[hasClubs] ?: false
                )
            }
    }

    override fun findById(id: UUID): City? =
        dsl.selectFrom(CITIES)
            .where(CITIES.ID.eq(id))
            .fetchOne()
            ?.let {
                City(
                    id = it.id!!,
                    countryCode = it.countryCode!!,
                    name = it.name!!,
                    region = it.region,
                    needsRegion = it.needsRegion ?: false,
                    isFeatured = it.isFeatured ?: false
                )
            }
}
