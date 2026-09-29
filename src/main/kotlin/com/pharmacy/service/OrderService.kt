package com.pharmacy.service

import com.pharmacy.domain.IllegalTransitionException
import com.pharmacy.domain.InvalidOrderException
import com.pharmacy.domain.OrderItem
import com.pharmacy.domain.OrderNotFoundException
import com.pharmacy.domain.OrderStatus
import com.pharmacy.domain.OrderStatus.RECEIVED
import com.pharmacy.domain.OutOfStockException
import com.pharmacy.domain.PrescriptionOrder
import com.pharmacy.messaging.OrderPublisher
import com.pharmacy.repository.OrderRepository
import com.pharmacy.repository.StockRepository
import com.pharmacy.sse.StatusHub
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The prescription-to-patient cycle.
 *
 * Two kinds of entry points:
 *  - queue consumers (checkInventory, enterPharmacistQueue, enterPackaging, failStuckOrder):
 *    idempotent, they apply the state changes;
 *  - staff/patient commands (submit, approve, reject, markPacked, collect): validate, then
 *    either publish the next message or apply the change.
 */
@Service
class OrderService(
    private val orders: OrderRepository,
    private val stock: StockRepository,
    private val inventory: InventoryService,
    private val publisher: OrderPublisher,
    private val hub: StatusHub,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun submit(patientName: String, requested: List<OrderItem>): PrescriptionOrder {
        val name = patientName.trim()
        if (name.isEmpty() || name.length > 60) {
            throw InvalidOrderException("Please enter the patient's name (up to 60 characters).")
        }
        val items = requested
            .groupBy { it.medication }
            .map { (medication, lines) -> OrderItem(medication, lines.sumOf { line -> line.quantity }) }
        if (items.isEmpty() || items.size > 10) {
            throw InvalidOrderException("A prescription needs between 1 and 10 medications.")
        }
        val known = stock.all().map { it.medication }.toSet()
        for (item in items) {
            if (item.medication !in known) throw InvalidOrderException("We don't stock '${item.medication}'.")
            if (item.quantity !in 1..30) {
                throw InvalidOrderException("Quantity for ${item.medication} must be between 1 and 30.")
            }
        }

        val order = orders.insert(UUID.randomUUID(), name, items, Instant.now())
        try {
            publisher.toInventory(order.id)
        } catch (e: AmqpException) {
            // The patient still gets a ticket. StuckOrderSweeper re-publishes once the broker is back.
            log.warn("Broker unavailable, order {} will be picked up by the sweeper", order.ticket, e)
        }
        return order
    }

    fun view(id: UUID): OrderView? = orders.find(id)?.let { toView(it) }

    fun findIdByTicket(ticket: String): UUID {
        val seq = ticket.filter { it.isDigit() }.toLongOrNull() ?: throw OrderNotFoundException(ticket)
        return (orders.findBySeq(seq) ?: throw OrderNotFoundException(ticket)).id
    }

    fun catalogue(): List<MedicationView> =
        stock.all().map { MedicationView(it.medication, it.category, it.quantity > 0) }

    fun board(): BoardView = BoardView(
        ready = orders.findByStatus(OrderStatus.READY).map { it.ticket },
        preparing = orders.findByStatus(OrderStatus.PACKAGING).map { it.ticket },
    )

    fun checkInventory(id: UUID) {
        val order = orders.find(id)
        if (order == null) {
            log.warn("Inventory message for unknown order {}", id)
            return
        }
        if (order.status != RECEIVED) return // duplicate delivery
        try {
            inventory.reserve(order)
        } catch (e: OutOfStockException) {
            move(
                id, OrderStatus.REJECTED,
                "Sorry, ${e.medication} is out of stock right now. Please speak to the pharmacist at the counter.",
                only = setOf(RECEIVED),
            )
            return
        }
        // If this throws, the retry policy re-runs the method (reserve is idempotent).
        publisher.toPharmacist(id)
    }

    fun enterPharmacistQueue(id: UUID) {
        move(id, OrderStatus.AWAITING_PHARMACIST, only = setOf(RECEIVED))
    }

    fun enterPackaging(id: UUID) {
        move(id, OrderStatus.PACKAGING, only = setOf(OrderStatus.AWAITING_PHARMACIST))
    }

    /** A message for this order died in the dead-letter queue while it was waiting in [stuckIn]. */
    fun failStuckOrder(id: UUID, stuckIn: OrderStatus) {
        val failed = move(
            id, OrderStatus.FAILED,
            "Something went wrong on our side. Please go to the pharmacy counter with your ticket.",
            only = setOf(stuckIn),
        )
        if (failed) log.error("Order {} failed after retries (stuck in {}); stock released", id, stuckIn)
    }

    fun approve(id: UUID) {
        requireStatus(id, OrderStatus.AWAITING_PHARMACIST)
        publisher.toPackaging(id)
    }

    fun reject(id: UUID, reason: String?) {
        val order = requireStatus(id, OrderStatus.AWAITING_PHARMACIST)
        val message = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
            ?: "The pharmacist needs to speak with you about this prescription. Please come to the counter."
        if (!move(id, OrderStatus.REJECTED, message, only = setOf(OrderStatus.AWAITING_PHARMACIST))) {
            throw IllegalTransitionException(order.ticket, order.status, OrderStatus.REJECTED)
        }
    }

    fun markPacked(id: UUID) {
        val order = requireStatus(id, OrderStatus.PACKAGING)
        if (!move(id, OrderStatus.READY, only = setOf(OrderStatus.PACKAGING))) {
            throw IllegalTransitionException(order.ticket, order.status, OrderStatus.READY)
        }
    }

    fun collect(id: UUID) {
        val order = requireStatus(id, OrderStatus.READY)
        if (!move(id, OrderStatus.COLLECTED, only = setOf(OrderStatus.READY))) {
            throw IllegalTransitionException(order.ticket, order.status, OrderStatus.COLLECTED)
        }
    }

    fun staffOverview(): StaffOverview {
        val cutoff = Instant.now().minus(Duration.ofHours(2))
        val attention = (orders.findByStatus(OrderStatus.FAILED) + orders.findByStatus(OrderStatus.REJECTED))
            .filter { it.updatedAt.isAfter(cutoff) }
            .sortedBy { it.seq }
        return StaffOverview(
            awaitingPharmacist = orders.findByStatus(OrderStatus.AWAITING_PHARMACIST).map(::toStaffOrder),
            packaging = orders.findByStatus(OrderStatus.PACKAGING).map(::toStaffOrder),
            ready = orders.findByStatus(OrderStatus.READY).map(::toStaffOrder),
            needsAttention = attention.map(::toStaffOrder),
            stock = stock.all(),
        )
    }

    private fun requireStatus(id: UUID, expected: OrderStatus): PrescriptionOrder {
        val order = orders.find(id) ?: throw OrderNotFoundException(id.toString())
        if (order.status != expected) throw IllegalTransitionException(order.ticket, order.status, expected)
        return order
    }

    /**
     * Applies a transition that the state machine allows, atomically (and, when the order
     * ends unsuccessfully, releases its reserved stock in the same transaction).
     * Returns false if the order was not in an allowed source status: duplicates are no-ops.
     */
    private fun move(
        id: UUID,
        to: OrderStatus,
        message: String? = null,
        only: Set<OrderStatus>? = null,
    ): Boolean {
        val allowedFrom = OrderStatus.entries.filter { it.canMoveTo(to) && (only == null || it in only) }.toSet()
        if (allowedFrom.isEmpty()) return false

        val changed = tx.execute {
            val ok = orders.transition(id, allowedFrom, to, message, Instant.now())
            if (ok && (to == OrderStatus.REJECTED || to == OrderStatus.FAILED)) {
                orders.find(id)?.let { inventory.release(it) }
            }
            ok
        } ?: false

        if (changed) refreshWatchers()
        return changed
    }

    /** Queue positions of everybody change when one order moves, so recompute all open trackers. */
    private fun refreshWatchers() {
        for (watched in hub.watchedIds()) {
            try {
                orders.find(watched)?.let { hub.push(watched, toView(it)) }
            } catch (e: Exception) {
                log.warn("Could not refresh live status for {}", watched, e)
            }
        }
    }

    private fun toStaffOrder(o: PrescriptionOrder) = StaffOrder(
        id = o.id.toString(),
        ticket = o.ticket,
        patientName = o.patientName,
        items = o.items.map { "${it.quantity} x ${it.medication}" },
        status = o.status.name,
        message = o.message,
        since = o.updatedAt.toString(),
    )

    private fun toView(o: PrescriptionOrder): OrderView {
        val ahead = when (o.status) {
            OrderStatus.AWAITING_PHARMACIST, OrderStatus.PACKAGING -> orders.countAhead(o.status, o.seq)
            else -> null
        }
        val (headline, detail) = when (o.status) {
            RECEIVED ->
                "We've received your prescription" to "Checking that everything is in stock."
            OrderStatus.AWAITING_PHARMACIST ->
                "The pharmacist is reviewing your prescription" to aheadText(ahead ?: 0)
            OrderStatus.PACKAGING ->
                "Your medication is being prepared" to aheadText(ahead ?: 0)
            OrderStatus.READY ->
                "Your prescription is ready!" to "Please go to the pharmacy counter. Your number is ${o.ticket}."
            OrderStatus.COLLECTED ->
                "All done, thank you!" to "Your prescription has been handed over. Take care."
            OrderStatus.REJECTED ->
                "We couldn't fill this prescription" to
                    (o.message ?: "Please speak to the pharmacist at the counter.")
            OrderStatus.FAILED ->
                "Something went wrong on our side" to
                    (o.message ?: "Please go to the pharmacy counter with your ticket.")
        }
        return OrderView(
            orderId = o.id.toString(),
            ticket = o.ticket,
            status = o.status.name,
            step = o.status.step,
            headline = headline,
            detail = detail,
            peopleAhead = ahead,
            terminal = o.status.isTerminal,
            needsAttention = o.status == OrderStatus.REJECTED || o.status == OrderStatus.FAILED,
        )
    }

    private fun aheadText(ahead: Int): String = when (ahead) {
        0 -> "You're next in line."
        1 -> "1 prescription ahead of yours."
        else -> "$ahead prescriptions ahead of yours."
    }
}
