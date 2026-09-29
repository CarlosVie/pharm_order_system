package com.pharmacy.repository

import com.pharmacy.domain.StockItem
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
class StockRepository(private val jdbc: JdbcTemplate) {

    private val mapper = RowMapper<StockItem> { rs, _ ->
        StockItem(rs.getString("medication"), rs.getString("category"), rs.getInt("quantity"))
    }

    fun all(): List<StockItem> =
        jdbc.query("SELECT medication, category, quantity FROM stock ORDER BY medication", mapper)

    /** Takes [qty] only if enough is left; a single atomic statement, so no overselling. */
    fun tryTake(medication: String, qty: Int): Boolean =
        jdbc.update(
            "UPDATE stock SET quantity = quantity - ? WHERE medication = ? AND quantity >= ?",
            qty, medication, qty,
        ) == 1

    fun giveBack(medication: String, qty: Int) {
        jdbc.update("UPDATE stock SET quantity = quantity + ? WHERE medication = ?", qty, medication)
    }
}
