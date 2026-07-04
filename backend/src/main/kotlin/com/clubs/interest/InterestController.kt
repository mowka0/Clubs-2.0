package com.clubs.interest

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/interests")
class InterestController(private val interestService: InterestService) {

    /** Автодополнение по префиксу для chip-инпута интересов. Глобально под JWT и rate-limit. */
    @GetMapping("/suggest")
    fun suggest(
        @RequestParam q: String,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<List<String>> =
        ResponseEntity.ok(interestService.suggest(q, limit))
}
