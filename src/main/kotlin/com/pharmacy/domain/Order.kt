package com.pharmacy.domain

import java.time.Instant
import java.util.UUID

data class OrderItem(val medication: String, val quantity: Int)

data class StockItem(val medication: String, val category: String, val quantity: Int)

data class PrescriptionOrder(
    val seq: Long,
    val id: UUID,
    val patientName: String,
    val items: List<OrderItem>,
    val status: OrderStatus,
    val stockReserved: Boolean,
    val message: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** The number shown to the patient and called out by the pharmacist, e.g. "A-007". */
    val ticket: String get() = "A-%03d".format(seq)
}
