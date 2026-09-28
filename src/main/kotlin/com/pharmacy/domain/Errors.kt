package com.pharmacy.domain

class OrderNotFoundException(ref: String) : RuntimeException("Order not found: $ref")

class InvalidOrderException(message: String) : RuntimeException(message)

class IllegalTransitionException(ticket: String, from: OrderStatus, to: OrderStatus) :
    RuntimeException("Order $ticket is $from and cannot move to $to")

class OutOfStockException(val medication: String) : RuntimeException("Out of stock: $medication")
