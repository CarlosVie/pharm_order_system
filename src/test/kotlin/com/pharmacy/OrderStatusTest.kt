package com.pharmacy

import com.pharmacy.domain.OrderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderStatusTest {

    @Test
    fun `happy path is allowed step by step`() {
        val path = listOf(
            OrderStatus.RECEIVED, OrderStatus.AWAITING_PHARMACIST, OrderStatus.PACKAGING,
            OrderStatus.READY, OrderStatus.COLLECTED,
        )
        path.zipWithNext().forEach { (from, to) -> assertTrue(from.canMoveTo(to), "$from -> $to") }
    }

    @Test
    fun `steps cannot be skipped or reversed`() {
        assertFalse(OrderStatus.RECEIVED.canMoveTo(OrderStatus.PACKAGING))
        assertFalse(OrderStatus.AWAITING_PHARMACIST.canMoveTo(OrderStatus.READY))
        assertFalse(OrderStatus.READY.canMoveTo(OrderStatus.PACKAGING))
    }

    @Test
    fun `terminal statuses have no exits`() {
        listOf(OrderStatus.COLLECTED, OrderStatus.REJECTED, OrderStatus.FAILED).forEach {
            assertTrue(it.isTerminal, "$it")
            assertEquals(emptySet(), it.nextStates())
        }
    }

    @Test
    fun `pharmacist can reject only before packaging starts`() {
        assertTrue(OrderStatus.AWAITING_PHARMACIST.canMoveTo(OrderStatus.REJECTED))
        assertFalse(OrderStatus.PACKAGING.canMoveTo(OrderStatus.REJECTED))
    }
}
