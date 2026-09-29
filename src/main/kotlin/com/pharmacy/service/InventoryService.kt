package com.pharmacy.service

import com.pharmacy.domain.OrderItem
import com.pharmacy.domain.OutOfStockException
import com.pharmacy.domain.PrescriptionOrder
import com.pharmacy.repository.OrderRepository
import com.pharmacy.repository.StockRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InventoryService(
    private val stock: StockRepository,
    private val orders: OrderRepository,
) {

    /**
     * All items or none, and idempotent: a redelivered message finds the reservation
     * already claimed and does nothing. Throwing rolls back the claim AND any items
     * already taken, so a partial reservation can never exist.
     */
    @Transactional
    fun reserve(order: PrescriptionOrder) {
        if (!orders.claimReservation(order.id)) return
        for (item: OrderItem in order.items) {
            if (!stock.tryTake(item.medication, item.quantity)) throw OutOfStockException(item.medication)
        }
    }

    /** Gives reserved stock back exactly once, whatever number of times it is called. */
    @Transactional
    fun release(order: PrescriptionOrder) {
        if (!orders.claimRelease(order.id)) return
        order.items.forEach { stock.giveBack(it.medication, it.quantity) }
    }
}
