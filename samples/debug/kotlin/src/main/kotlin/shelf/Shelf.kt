// The shelf's items and the checkout loop: a watch on `total`, a map that grows, and a breakpoint in a lambda.
package shelf

import java.util.TreeMap

/** One shelf item: a data class, so Variables shows its four fields. */
data class Item(val sku: String, val name: String, val quantity: Int, val price: Double)

/** What checkout produces: the printed lines and the grand total. */
data class Receipt(val lines: List<String>, val total: Double)

val SHELF = listOf(
    Item("A-1", "Pencil", 12, 0.40),
    Item("A-2", "Notebook", 3, 2.75),
    Item("B-1", "Stapler", 1, 8.90),
    Item("B-2", "Tape", 4, 1.20),
    Item("C-1", "Marker", 10, 1.05),
    Item("C-2", "Eraser", 25, 0.30),
    Item("C-3", "Ruler", 2, 1.50),
    Item("D-1", "Glue", 6, 2.10),
)

/** Totals up to [limit] items: `total`, `lines` and `byPrefix` change on every pass. */
fun checkout(shelf: List<Item>, limit: Int): Receipt {
    val lines = mutableListOf<String>()
    val byPrefix = TreeMap<Char, Int>()
    var total = 0.0
    for (item in shelf.take(limit)) {
        val discount = Pricing.discountFor(item) // break here: Step Into, then look at `discount` per item
        val price = Pricing.apply(discount, item.price)
        val subtotal = price * item.quantity
        lines += "%-4s %-9s %3d x %5.2f = %7.2f".format(item.sku, item.name, item.quantity, price, subtotal)
        byPrefix.merge(item.sku[0], item.quantity, Int::plus)
        total += subtotal // break here: a watch on `total`, or hover `item.name`
    }
    val cheap = shelf.asSequence()
        .filter { it.price < 1.0 } // break here: inside a lambda (the frame reads `Shelf.checkout { lambda }`)
        .map { it.name }
        .sorted()
        .toList()
    lines += "cheap: ${cheap.joinToString(", ")}  by prefix: $byPrefix"
    return Receipt(lines, total)
}
