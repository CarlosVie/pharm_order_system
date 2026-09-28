package com.pharmacy.infrastructure.persistence

import com.pharmacy.domain.StockItem
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
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

    fun insertIfMissing(item: StockItem) {
        val exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stock WHERE medication = ?", Int::class.javaObjectType, item.medication,
        ) ?: 0
        if (exists == 0) {
            jdbc.update(
                "INSERT INTO stock (medication, category, quantity) VALUES (?, ?, ?)",
                item.medication, item.category, item.quantity,
            )
        }
    }
}

/** Seeds the pharmacy's stock on first start; never overwrites quantities afterwards. */
@Component
class StockSeeder(private val stock: StockRepository) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        SEED.forEach { stock.insertIfMissing(it) }
    }

    companion object {
        val SEED = listOf(
            StockItem("Amoxicillin", "Antibiotic", 40),
            StockItem("Ibuprofen", "Pain relief", 60),
            StockItem("Lisinopril", "Blood pressure", 35),
            StockItem("Metformin", "Diabetes", 45),
            StockItem("Atorvastatin", "Cholesterol", 30),
            StockItem("Omeprazole", "Acid reflux", 40),
            StockItem("Cetirizine", "Allergy", 50),
            // Deliberately scarce, handy for demoing the out-of-stock path.
            StockItem("Salbutamol", "Asthma", 2),
        )
    }
}
