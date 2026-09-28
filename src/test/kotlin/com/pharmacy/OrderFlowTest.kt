package com.pharmacy

import com.pharmacy.application.OrderService
import com.pharmacy.domain.IllegalTransitionException
import com.pharmacy.domain.OrderItem
import com.pharmacy.domain.OrderStatus
import com.pharmacy.infrastructure.messaging.OrderPublisher
import com.pharmacy.infrastructure.persistence.OrderRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Drives the use cases directly, playing the role of the queue consumers, so the whole
 * flow and its failure paths are tested without a broker. The publisher is mocked.
 */
@SpringBootTest(
    properties = [
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.datasource.url=jdbc:h2:mem:flowtest;DB_CLOSE_DELAY=-1",
    ],
)
class OrderFlowTest {

    @MockBean
    lateinit var publisher: OrderPublisher

    @Autowired
    lateinit var service: OrderService

    @Autowired
    lateinit var orders: OrderRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun resetData() {
        jdbc.update("DELETE FROM order_items")
        jdbc.update("DELETE FROM orders")
        jdbc.update("UPDATE stock SET quantity = 10")
    }

    private fun stockOf(medication: String): Int =
        jdbc.queryForObject("SELECT quantity FROM stock WHERE medication = ?", Int::class.javaObjectType, medication)!!

    private fun statusOf(order: com.pharmacy.domain.PrescriptionOrder) = orders.find(order.id)!!.status

    @Test
    fun `full cycle from prescription to collected`() {
        val order = service.submit("Ana", listOf(OrderItem("Amoxicillin", 2)))
        verify(publisher).toInventory(order.id)
        assertEquals(OrderStatus.RECEIVED, statusOf(order))

        service.checkInventory(order.id)
        verify(publisher).toPharmacist(order.id)
        assertEquals(8, stockOf("Amoxicillin"))

        service.enterPharmacistQueue(order.id)
        assertEquals(OrderStatus.AWAITING_PHARMACIST, statusOf(order))

        service.approve(order.id)
        verify(publisher).toPackaging(order.id)
        service.enterPackaging(order.id)
        assertEquals(OrderStatus.PACKAGING, statusOf(order))

        service.markPacked(order.id)
        assertEquals(OrderStatus.READY, statusOf(order))

        service.collect(order.id)
        assertEquals(OrderStatus.COLLECTED, statusOf(order))
        assertEquals(8, stockOf("Amoxicillin")) // dispensed stock stays gone
    }

    @Test
    fun `out of stock rejects the whole prescription and reserves nothing`() {
        val order = service.submit("Ben", listOf(OrderItem("Ibuprofen", 2), OrderItem("Amoxicillin", 11)))

        service.checkInventory(order.id)

        assertEquals(OrderStatus.REJECTED, statusOf(order))
        assertEquals(10, stockOf("Ibuprofen"))
        assertEquals(10, stockOf("Amoxicillin"))
    }

    @Test
    fun `duplicate delivery reserves stock only once`() {
        val order = service.submit("Carla", listOf(OrderItem("Metformin", 3)))

        service.checkInventory(order.id)
        service.checkInventory(order.id)

        assertEquals(7, stockOf("Metformin"))
    }

    @Test
    fun `pharmacist rejection gives the stock back`() {
        val order = service.submit("Dan", listOf(OrderItem("Lisinopril", 4)))
        service.checkInventory(order.id)
        service.enterPharmacistQueue(order.id)
        assertEquals(6, stockOf("Lisinopril"))

        service.reject(order.id, "Dose looks wrong")

        assertEquals(OrderStatus.REJECTED, statusOf(order))
        assertEquals(10, stockOf("Lisinopril"))
    }

    @Test
    fun `dead-lettered message fails the order and releases stock`() {
        val order = service.submit("Eva", listOf(OrderItem("Atorvastatin", 5)))
        service.checkInventory(order.id)
        assertEquals(5, stockOf("Atorvastatin"))

        service.failStuckOrder(order.id, OrderStatus.RECEIVED)

        assertEquals(OrderStatus.FAILED, statusOf(order))
        assertEquals(10, stockOf("Atorvastatin"))
    }

    @Test
    fun `stale dead letter cannot fail an order that already moved on`() {
        val order = service.submit("Filipe", listOf(OrderItem("Cetirizine", 1)))
        service.checkInventory(order.id)
        service.enterPharmacistQueue(order.id)

        service.failStuckOrder(order.id, OrderStatus.RECEIVED)

        assertEquals(OrderStatus.AWAITING_PHARMACIST, statusOf(order))
    }

    @Test
    fun `staff actions are rejected when the order is in the wrong state`() {
        val order = service.submit("Gil", listOf(OrderItem("Omeprazole", 1)))

        assertFailsWith<IllegalTransitionException> { service.approve(order.id) }
        assertFailsWith<IllegalTransitionException> { service.markPacked(order.id) }
    }

    @Test
    fun `patient view never exposes name or medication`() {
        val order = service.submit("Helena", listOf(OrderItem("Ibuprofen", 1)))
        val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .writeValueAsString(service.view(order.id))

        assert(!json.contains("Helena")) { json }
        assert(!json.contains("Ibuprofen")) { json }
    }
}
