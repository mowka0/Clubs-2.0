package com.clubs.city

import com.clubs.common.exception.ValidationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CityService(private val cityRepository: CityRepository) {

    @Transactional(readOnly = true)
    fun listAll(): List<CityDto> = cityRepository.findAll()

    /**
     * Город по id для записи в клуб или профиль. Единственная точка, где проверяется, что город
     * вообще существует: клиент присылает только `cityId`, произвольный текст города прислать
     * не может, поэтому денормализованное `clubs.city` всегда производно от справочника.
     */
    @Transactional(readOnly = true)
    fun requireCity(cityId: UUID): City =
        cityRepository.findById(cityId) ?: throw ValidationException("Город не найден в справочнике")
}
