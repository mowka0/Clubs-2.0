package com.clubs.geo

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Результат прямого геокодинга: человеческий адрес + точка (порядок полей привычный, lat/lon). */
data class GeocodeResultDto(
    val address: String,
    val lat: Double,
    val lon: Double
)

@RestController
@RequestMapping("/api/geo")
class GeoController(private val geocoderService: GeocoderService) {

    /**
     * Поиск адреса для пикера места события. Под JWT и общим rate-limit, как весь остальной /api.
     *
     * `204 No Content` = адрес не найден (пользователю «уточните запрос»), `503` = геокодер
     * недоступен («попробуйте позже»). Раньше это различие делал фронтенд по HTTP-коду Яндекса,
     * ходя к нему напрямую с публичным ключом; теперь ключ живёт только на сервере.
     */
    @GetMapping("/geocode")
    fun geocode(@RequestParam q: String): ResponseEntity<GeocodeResultDto> {
        val result = geocoderService.geocode(q) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(result)
    }
}
