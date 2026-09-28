package com.pharmacy.infrastructure.sse

import com.pharmacy.application.OrderView
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Keeps the open "live status" connections of patients in the waiting room.
 * A new connection immediately gets the current status, so reconnects never show stale data.
 */
@Component
class StatusHub {

    private val log = LoggerFactory.getLogger(javaClass)
    private val emitters = ConcurrentHashMap<UUID, CopyOnWriteArraySet<SseEmitter>>()
    private val lastPushed = ConcurrentHashMap<UUID, OrderView>()

    fun subscribe(orderId: UUID, snapshot: () -> OrderView?): SseEmitter {
        val emitter = SseEmitter(SSE_TIMEOUT_MS)
        // Register first, then read the snapshot: a change between the two is still delivered.
        emitters.compute(orderId) { _, set -> (set ?: CopyOnWriteArraySet()).also { it.add(emitter) } }
        emitter.onCompletion { unregister(orderId, emitter) }
        emitter.onTimeout { emitter.complete() }
        emitter.onError { unregister(orderId, emitter) }
        snapshot()?.let { sendTo(orderId, emitter, it) }
        return emitter
    }

    fun watchedIds(): Set<UUID> = emitters.keys.toSet()

    /** Sends to everyone watching [orderId], but only if the view changed. */
    fun push(orderId: UUID, view: OrderView) {
        if (lastPushed.put(orderId, view) == view) return
        emitters[orderId]?.forEach { sendTo(orderId, it, view) }
    }

    /** Keeps proxies from closing idle connections and detects dead clients. */
    @Scheduled(fixedRate = 15_000)
    fun heartbeat() {
        for ((id, set) in emitters) {
            for (emitter in set) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"))
                } catch (e: Exception) {
                    unregister(id, emitter)
                }
            }
        }
    }

    private fun sendTo(orderId: UUID, emitter: SseEmitter, view: OrderView) {
        try {
            emitter.send(
                SseEmitter.event().name("status").reconnectTime(3000).data(view, MediaType.APPLICATION_JSON),
            )
        } catch (e: Exception) {
            log.debug("Dropping dead SSE connection for {}", orderId)
            unregister(orderId, emitter)
        }
    }

    private fun unregister(orderId: UUID, emitter: SseEmitter) {
        emitters.computeIfPresent(orderId) { _, set ->
            set.remove(emitter)
            if (set.isEmpty()) null else set
        }
        if (!emitters.containsKey(orderId)) lastPushed.remove(orderId)
    }

    companion object {
        private const val SSE_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
