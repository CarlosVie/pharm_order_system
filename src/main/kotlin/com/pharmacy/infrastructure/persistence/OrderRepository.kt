package com.pharmacy.infrastructure.persistence

import com.pharmacy.domain.OrderItem
import com.pharmacy.domain.OrderStatus
import com.pharmacy.domain.PrescriptionOrder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Plain JDBC on purpose: every state change is one guarded UPDATE, which is what makes
 * duplicate / concurrent actions safe. No ORM magic to explain.
 */
@Repository
class OrderRepository(private val jdbc: JdbcTemplate) {

    private class Row(
        val seq: Long,
        val id: UUID,
        val patientName: String,
        val status: OrderStatus,
        val stockReserved: Boolean,
        val message: String?,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private val rowMapper = RowMapper<Row> { rs, _ ->
        Row(
            seq = rs.getLong("seq"),
            id = UUID.fromString(rs.getString("id")),
            patientName = rs.getString("patient_name"),
            status = OrderStatus.valueOf(rs.getString("status")),
            stockReserved = rs.getBoolean("stock_reserved"),
            message = rs.getString("message"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }

    private val itemMapper = RowMapper<OrderItem> { rs, _ ->
        OrderItem(rs.getString("medication"), rs.getInt("quantity"))
    }

    private fun hydrate(rows: List<Row>): List<PrescriptionOrder> = rows.map { r ->
        val items = jdbc.query(
            "SELECT medication, quantity FROM order_items WHERE order_seq = ? ORDER BY medication",
            itemMapper,
            r.seq,
        )
        PrescriptionOrder(
            r.seq, r.id, r.patientName, items, r.status, r.stockReserved, r.message, r.createdAt, r.updatedAt,
        )
    }

    /** Order + items in one transaction; committed before the caller publishes to RabbitMQ. */
    @Transactional
    fun insert(id: UUID, patientName: String, items: List<OrderItem>, now: Instant): PrescriptionOrder {
        jdbc.update(
            "INSERT INTO orders (id, patient_name, status, stock_reserved, created_at, updated_at) " +
                "VALUES (?, ?, ?, FALSE, ?, ?)",
            id.toString(), patientName, OrderStatus.RECEIVED.name, Timestamp.from(now), Timestamp.from(now),
        )
        val seq = jdbc.queryForObject(
            "SELECT seq FROM orders WHERE id = ?", Long::class.javaObjectType, id.toString(),
        )!!
        items.forEach {
            jdbc.update(
                "INSERT INTO order_items (order_seq, medication, quantity) VALUES (?, ?, ?)",
                seq, it.medication, it.quantity,
            )
        }
        return find(id)!!
    }

    fun find(id: UUID): PrescriptionOrder? =
        hydrate(jdbc.query("SELECT * FROM orders WHERE id = ?", rowMapper, id.toString())).firstOrNull()

    fun findBySeq(seq: Long): PrescriptionOrder? =
        hydrate(jdbc.query("SELECT * FROM orders WHERE seq = ?", rowMapper, seq)).firstOrNull()

    fun findByStatus(status: OrderStatus): List<PrescriptionOrder> =
        hydrate(jdbc.query("SELECT * FROM orders WHERE status = ? ORDER BY seq", rowMapper, status.name))

    /** Orders sitting in [status] without a change since [olderThan]. */
    fun findStale(status: OrderStatus, olderThan: Instant): List<PrescriptionOrder> =
        hydrate(
            jdbc.query(
                "SELECT * FROM orders WHERE status = ? AND updated_at < ? ORDER BY seq",
                rowMapper, status.name, Timestamp.from(olderThan),
            ),
        )

    /** Same-status orders that arrived earlier = people ahead in that queue. */
    fun countAhead(status: OrderStatus, seq: Long): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE status = ? AND seq < ?",
            Int::class.javaObjectType, status.name, seq,
        ) ?: 0

    /**
     * Atomic, guarded transition. Returns false when the order was not in one of [from]
     * (duplicate message, concurrent action, ...), in which case nothing changed.
     */
    fun transition(id: UUID, from: Set<OrderStatus>, to: OrderStatus, message: String?, now: Instant): Boolean {
        val placeholders = from.joinToString(",") { "?" }
        val args = mutableListOf<Any?>(to.name, message, Timestamp.from(now), id.toString())
        args.addAll(from.map { it.name })
        return jdbc.update(
            "UPDATE orders SET status = ?, message = ?, updated_at = ? WHERE id = ? AND status IN ($placeholders)",
            *args.toTypedArray(),
        ) == 1
    }

    fun touch(id: UUID, now: Instant) {
        jdbc.update("UPDATE orders SET updated_at = ? WHERE id = ?", Timestamp.from(now), id.toString())
    }

    /** true only for the caller that flips the flag: makes stock reservation idempotent. */
    fun claimReservation(id: UUID): Boolean =
        jdbc.update("UPDATE orders SET stock_reserved = TRUE WHERE id = ? AND stock_reserved = FALSE", id.toString()) == 1

    /** true only for the caller that flips the flag back: makes stock release idempotent. */
    fun claimRelease(id: UUID): Boolean =
        jdbc.update("UPDATE orders SET stock_reserved = FALSE WHERE id = ? AND stock_reserved = TRUE", id.toString()) == 1
}
