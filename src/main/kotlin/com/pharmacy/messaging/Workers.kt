package com.pharmacy.messaging

import com.pharmacy.service.OrderService
import com.pharmacy.domain.OrderStatus
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.support.AmqpHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import java.util.UUID

// Listeners stay thin: parse the id, call the use case. A thrown exception triggers the
// retry policy from application.properties and finally dead-lettering.

@Component
class InventoryWorker(private val service: OrderService) {
    @RabbitListener(queues = [Topology.INVENTORY_QUEUE])
    fun handle(orderId: String) {
        service.checkInventory(UUID.fromString(orderId))
    }
}

/** Puts the order on the pharmacist's worklist. */
@Component
class PharmacistQueueWorker(private val service: OrderService) {
    @RabbitListener(queues = [Topology.PHARMACIST_QUEUE])
    fun handle(orderId: String) {
        service.enterPharmacistQueue(UUID.fromString(orderId))
    }
}

/** Puts the approved order on the packager's worklist. */
@Component
class PackagingQueueWorker(private val service: OrderService) {
    @RabbitListener(queues = [Topology.PACKAGING_QUEUE])
    fun handle(orderId: String) {
        service.enterPackaging(UUID.fromString(orderId))
    }
}

/**
 * Receives messages that exhausted their retries. The routing key says which stage failed;
 * the order is failed only if it is still waiting for that stage.
 */
@Component
class DeadLetterWorker(private val service: OrderService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @RabbitListener(queues = [Topology.DEAD_LETTER_QUEUE])
    fun handle(orderId: String, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) deadKey: String) {
        val id = runCatching { UUID.fromString(orderId) }.getOrNull()
        if (id == null) {
            log.error("Dead-lettered message with unparseable order id '{}' ({})", orderId, deadKey)
            return
        }
        val stuckIn = when (deadKey) {
            Topology.DEAD_INVENTORY, Topology.DEAD_PHARMACIST -> OrderStatus.RECEIVED
            Topology.DEAD_PACKAGING -> OrderStatus.AWAITING_PHARMACIST
            else -> {
                log.error("Dead-lettered message for {} with unknown key '{}'", id, deadKey)
                return
            }
        }
        service.failStuckOrder(id, stuckIn)
    }
}
