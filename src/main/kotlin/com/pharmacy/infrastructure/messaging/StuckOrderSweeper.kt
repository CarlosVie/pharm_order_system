package com.pharmacy.infrastructure.messaging

import com.pharmacy.domain.OrderStatus
import com.pharmacy.infrastructure.persistence.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Safety net for the publish step. If the broker was down when an order arrived (or a
 * message was lost), the order sits in RECEIVED. Every 15s we re-send the next message for
 * anything stuck there for 20s+. Consumers are idempotent, so a duplicate is harmless.
 */
@Component
class StuckOrderSweeper(
    private val orders: OrderRepository,
    private val publisher: OrderPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 15_000)
    fun sweep() {
        val now = Instant.now()
        val stuck = orders.findStale(OrderStatus.RECEIVED, now.minusSeconds(STALE_AFTER_SECONDS))
        for (order in stuck) {
            try {
                if (order.stockReserved) publisher.toPharmacist(order.id) else publisher.toInventory(order.id)
                orders.touch(order.id, now) // check it again after another STALE_AFTER_SECONDS
                log.info("Re-published stuck order {}", order.ticket)
            } catch (e: AmqpException) {
                log.warn("Broker still unavailable, will retry order {} later", order.ticket)
                return
            }
        }
    }

    companion object {
        const val STALE_AFTER_SECONDS = 20L
    }
}
