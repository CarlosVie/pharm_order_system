package com.pharmacy.web

import com.pharmacy.domain.IllegalTransitionException
import com.pharmacy.domain.InvalidOrderException
import com.pharmacy.domain.OrderNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ApiError(val error: String)

/** One place that decides which failure becomes which HTTP status. */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(OrderNotFoundException::class)
    fun notFound(e: OrderNotFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError("We couldn't find that order."))

    @ExceptionHandler(InvalidOrderException::class)
    fun invalid(e: InvalidOrderException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError(e.message ?: "Invalid request."))

    @ExceptionHandler(IllegalTransitionException::class)
    fun conflict(e: IllegalTransitionException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError("That order has already moved on. Refresh the list."))

    @ExceptionHandler(AmqpException::class)
    fun brokerDown(e: AmqpException): ResponseEntity<ApiError> {
        log.warn("RabbitMQ unavailable", e)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiError("Messaging is temporarily unavailable. Nothing was changed, please try again."))
    }
}
