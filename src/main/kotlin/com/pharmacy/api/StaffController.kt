package com.pharmacy.api

import com.pharmacy.service.OrderService
import com.pharmacy.service.StaffOverview
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/staff")
class StaffController(private val service: OrderService) {

    @GetMapping("/overview")
    fun overview(): StaffOverview = service.staffOverview()

    @PostMapping("/orders/{id}/approve")
    fun approve(@PathVariable("id") id: UUID) {
        service.approve(id)
    }

    @PostMapping("/orders/{id}/reject")
    fun reject(@PathVariable("id") id: UUID, @RequestParam("reason", required = false) reason: String?) {
        service.reject(id, reason)
    }

    @PostMapping("/orders/{id}/packed")
    fun packed(@PathVariable("id") id: UUID) {
        service.markPacked(id)
    }

    @PostMapping("/orders/{id}/collected")
    fun collected(@PathVariable("id") id: UUID) {
        service.collect(id)
    }
}
