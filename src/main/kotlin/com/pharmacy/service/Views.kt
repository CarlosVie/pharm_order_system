package com.pharmacy.service

import com.pharmacy.domain.StockItem

/** What the patient's phone renders. Deliberately contains no name and no medication. */
data class OrderView(
    val orderId: String,
    val ticket: String,
    val status: String,
    val step: Int,
    val headline: String,
    val detail: String,
    val peopleAhead: Int?,
    val terminal: Boolean,
    val needsAttention: Boolean,
)

/** For the waiting-room screen: ticket numbers only. */
data class BoardView(val ready: List<String>, val preparing: List<String>)

data class MedicationView(val name: String, val category: String, val inStock: Boolean)

data class StaffOrder(
    val id: String,
    val ticket: String,
    val patientName: String,
    val items: List<String>,
    val status: String,
    val message: String?,
    val since: String,
)

data class StaffOverview(
    val awaitingPharmacist: List<StaffOrder>,
    val packaging: List<StaffOrder>,
    val ready: List<StaffOrder>,
    val needsAttention: List<StaffOrder>,
    val stock: List<StockItem>,
)
