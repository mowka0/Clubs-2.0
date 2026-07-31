package com.clubs.city

import java.util.UUID

interface CityRepository {

    /** Весь справочник для пикера, отсортированный по стране и населению. */
    fun findAll(): List<CityDto>

    /** Город по id; null — такого города в справочнике нет. */
    fun findById(id: UUID): City?
}
