package com.clubs

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ClubsApplication

fun main(args: Array<String>) {
    runApplication<ClubsApplication>(*args)
}
