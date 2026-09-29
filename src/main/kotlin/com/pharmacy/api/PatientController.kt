package com.pharmacy.api

import com.pharmacy.service.BoardView
import com.pharmacy.service.MedicationView
import com.pharmacy.service.OrderService
import com.pharmacy.service.OrderView
import com.pharmacy.domain.OrderItem
import com.pharmacy.domain.OrderNotFoundException
import com.pharmacy.sse.StatusHub
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

data class ItemRequest(val medication: String, val quantity: Int)
data class CreateOrderRequest(val patientName: String, val items: List<ItemRequest>)
data class CreatedOrder(val orderId: String, val ticket: String)
data class TicketLookup(val orderId: String)

@RestController
@RequestMapping("/api")
class PatientController(
    private val service: OrderService,
    private val hub: StatusHub,
) {

    @GetMapping("/medications")
    fun medications(): List<MedicationView> = service.catalogue()

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateOrderRequest): CreatedOrder {
        val order = service.submit(
            request.patientName,
            request.items.map { OrderItem(it.medication, it.quantity) },
        )
        return CreatedOrder(order.id.toString(), order.ticket)
    }

    @GetMapping("/orders/{id}")
    fun get(@PathVariable("id") id: UUID): OrderView =
        service.view(id) ?: throw OrderNotFoundException(id.toString())

    /** Live status for the waiting room (Server-Sent Events). First event = current status. */
    @GetMapping("/orders/{id}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable("id") id: UUID): SseEmitter {
        if (service.view(id) == null) throw OrderNotFoundException(id.toString())
        return hub.subscribe(id) { service.view(id) }
    }

    @GetMapping("/tickets/{ticket}")
    fun byTicket(@PathVariable("ticket") ticket: String): TicketLookup =
        TicketLookup(service.findIdByTicket(ticket).toString())

    @GetMapping("/board")
    fun board(): BoardView = service.board()
}
