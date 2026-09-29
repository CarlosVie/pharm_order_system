package com.pharmacy.messaging

import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Messages carry only the order id. The database is the source of truth, so a late or
 * duplicated message can never apply stale data. Throws AmqpException if the broker is down.
 */
@Component
class OrderPublisher(private val rabbit: RabbitTemplate) {

    fun toInventory(orderId: UUID) = send(Topology.INVENTORY_KEY, orderId)

    fun toPharmacist(orderId: UUID) = send(Topology.PHARMACIST_KEY, orderId)

    fun toPackaging(orderId: UUID) = send(Topology.PACKAGING_KEY, orderId)

    private fun send(routingKey: String, orderId: UUID) {
        rabbit.convertAndSend(Topology.EXCHANGE, routingKey, orderId.toString())
    }
}
