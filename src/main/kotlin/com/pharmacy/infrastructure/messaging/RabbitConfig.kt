package com.pharmacy.infrastructure.messaging

import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Declared on startup by Spring's RabbitAdmin; safe to run repeatedly. */
@Configuration
class RabbitConfig {

    @Bean
    fun ordersExchange(): DirectExchange = DirectExchange(Topology.EXCHANGE, true, false)

    @Bean
    fun deadLetterExchange(): DirectExchange = DirectExchange(Topology.DEAD_LETTER_EXCHANGE, true, false)

    @Bean
    fun inventoryQueue(): Queue = workQueue(Topology.INVENTORY_QUEUE, Topology.DEAD_INVENTORY)

    @Bean
    fun pharmacistQueue(): Queue = workQueue(Topology.PHARMACIST_QUEUE, Topology.DEAD_PHARMACIST)

    @Bean
    fun packagingQueue(): Queue = workQueue(Topology.PACKAGING_QUEUE, Topology.DEAD_PACKAGING)

    @Bean
    fun deadLetterQueue(): Queue = QueueBuilder.durable(Topology.DEAD_LETTER_QUEUE).build()

    @Bean
    fun inventoryBinding(): Binding =
        BindingBuilder.bind(inventoryQueue()).to(ordersExchange()).with(Topology.INVENTORY_KEY)

    @Bean
    fun pharmacistBinding(): Binding =
        BindingBuilder.bind(pharmacistQueue()).to(ordersExchange()).with(Topology.PHARMACIST_KEY)

    @Bean
    fun packagingBinding(): Binding =
        BindingBuilder.bind(packagingQueue()).to(ordersExchange()).with(Topology.PACKAGING_KEY)

    @Bean
    fun deadInventoryBinding(): Binding =
        BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(Topology.DEAD_INVENTORY)

    @Bean
    fun deadPharmacistBinding(): Binding =
        BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(Topology.DEAD_PHARMACIST)

    @Bean
    fun deadPackagingBinding(): Binding =
        BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(Topology.DEAD_PACKAGING)

    private fun workQueue(name: String, deadKey: String): Queue =
        QueueBuilder.durable(name)
            .withArgument("x-dead-letter-exchange", Topology.DEAD_LETTER_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", deadKey)
            .build()
}
