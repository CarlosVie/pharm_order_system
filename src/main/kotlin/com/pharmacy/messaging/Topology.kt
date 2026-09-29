package com.pharmacy.messaging

/** Names of everything we declare in RabbitMQ. */
object Topology {
    const val EXCHANGE = "pharmacy.orders"

    const val INVENTORY_QUEUE = "pharmacy.inventory"
    const val PHARMACIST_QUEUE = "pharmacy.pharmacist"
    const val PACKAGING_QUEUE = "pharmacy.packaging"

    const val INVENTORY_KEY = "inventory"
    const val PHARMACIST_KEY = "pharmacist"
    const val PACKAGING_KEY = "packaging"

    const val DEAD_LETTER_EXCHANGE = "pharmacy.dlx"
    const val DEAD_LETTER_QUEUE = "pharmacy.dead-letter"
    const val DEAD_INVENTORY = "dead.inventory"
    const val DEAD_PHARMACIST = "dead.pharmacist"
    const val DEAD_PACKAGING = "dead.packaging"
}
