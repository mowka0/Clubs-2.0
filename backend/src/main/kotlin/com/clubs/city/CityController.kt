package com.clubs.city

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/cities")
class CityController(private val cityService: CityService) {

    /**
     * Справочник целиком (540 записей, ~40 КБ) — фронт кеширует его и ищет локально, без сети.
     * Под JWT, как остальные защищённые эндпоинты: пикер открывается только авторизованному.
     */
    @GetMapping
    fun list(): ResponseEntity<List<CityDto>> = ResponseEntity.ok(cityService.listAll())
}
