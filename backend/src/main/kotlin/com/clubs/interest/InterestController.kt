package com.clubs.interest

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/interests")
class InterestController(private val interestService: InterestService) {

    /**
     * Автодополнение по префиксу для chip-инпута интересов. Глобально под JWT и rate-limit.
     *
     * `clubsOnly=true` — режим поиска каталога: подсказываются только темы, которыми размечен
     * хотя бы один клуб. Без него выдача содержала бы интересы из профилей, по которым в каталоге
     * пусто, — тап по такой подсказке упирался бы в «ничего не найдено».
     */
    @GetMapping("/suggest")
    fun suggest(
        @RequestParam q: String,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(defaultValue = "false") clubsOnly: Boolean
    ): ResponseEntity<List<String>> =
        ResponseEntity.ok(interestService.suggest(q, limit, clubsOnly))

    /**
     * Темы полки для чипов при разметке клуба (club-interests). Словарь общий и публичный
     * по смыслу — отдаётся любому авторизованному, как и автодополнение выше.
     */
    @GetMapping
    fun byCategory(
        @RequestParam category: String,
        @RequestParam(defaultValue = "24") limit: Int
    ): ResponseEntity<List<String>> =
        ResponseEntity.ok(interestService.suggestByCategory(category, limit))
}
