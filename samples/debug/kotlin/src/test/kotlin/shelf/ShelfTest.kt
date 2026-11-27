// All green JUnit 5 tests, for the test gutter ▶, Run Test at Caret and Debug Test at Caret (`gradle test`).
package shelf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ShelfTest {

    @Test
    fun `checkout totals every item`() {
        val receipt = checkout(SHELF, SHELF.size)
        assertEquals(9, receipt.lines.size) // 8 items + the "cheap:" summary line
        assertEquals(55.71, receipt.total, 0.01) // break here, then Debug Test at Caret: step into checkout
    }

    @Test
    fun checkoutStopsAtTheLimit() {
        assertEquals(3, checkout(SHELF, 2).lines.size)
    }

    @ParameterizedTest
    @CsvSource("12, 1.00, 0.855", "1, 8.90, 7.565", "4, 1.20, 1.20")
    fun discountsFollowQuantityAndPrice(quantity: Int, price: Double, expected: Double) {
        val item = Item("Z-1", "Test", quantity, price)
        assertEquals(expected, Pricing.apply(Pricing.discountFor(item), price), 0.001)
    }

    @Test
    fun `a word is not a quantity`() {
        assertNull(parseQuantity("seven"))
        assertEquals(3, parseQuantity("3"))
    }

    @Test
    fun restockCountsEveryWorker() {
        assertEquals(480, restock(3))
    }
}
