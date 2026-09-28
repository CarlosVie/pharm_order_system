package com.pharmacy

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class PharmacyApplication

fun main(args: Array<String>) {
    runApplication<PharmacyApplication>(*args)
}
