package com.pharmacy.domain

/**
 * The single source of truth for which order transitions are legal.
 * [step] is what the patient-facing progress bar shows (0 = not part of the happy path).
 */
enum class OrderStatus(val step: Int) {
    RECEIVED(1),
    AWAITING_PHARMACIST(2),
    PACKAGING(3),
    READY(4),
    COLLECTED(5),
    REJECTED(0),
    FAILED(0);

    fun nextStates(): Set<OrderStatus> = when (this) {
        RECEIVED -> setOf(AWAITING_PHARMACIST, REJECTED, FAILED)
        AWAITING_PHARMACIST -> setOf(PACKAGING, REJECTED, FAILED)
        PACKAGING -> setOf(READY, FAILED)
        READY -> setOf(COLLECTED)
        COLLECTED, REJECTED, FAILED -> emptySet()
    }

    fun canMoveTo(next: OrderStatus): Boolean = next in nextStates()

    val isTerminal: Boolean get() = nextStates().isEmpty()
}
