// The shelf, in Groovy: a script that uses three class files beside it (Item, Pricing, Restock), which groovy
// compiles from source as the script needs them. Open this file and press F5 (Debug): it runs under Editora's
// Groovy debugger. Every suggested spot is a "break here" comment. `groovy shelf.groovy` runs it outside Editora,
// `groovy shelf.groovy 3` checks out three items, a word is a caught error, and `crash` throws an uncaught one.

def items = [
    new Item('A-1', 'Pencil', 12, 0.40),
    new Item('A-2', 'Notebook', 3, 2.75),
    new Item('B-1', 'Stapler', 1, 8.90),
    new Item('B-2', 'Tape', 4, 1.20),
    new Item('C-1', 'Marker', 10, 1.05),
    new Item('C-2', 'Eraser', 25, 0.30),
    new Item('C-3', 'Ruler', 2, 1.50),
    new Item('D-1', 'Glue', 6, 2.10),
]

/** Totals up to limit items: total, lines and byPrefix change on every pass. */
def checkout(List<Item> shelf, int limit) {
    def lines = []
    def byPrefix = new TreeMap<String, Integer>()
    def total = 0.0
    for (item in shelf.take(limit)) {
        def discount = Pricing.discountFor(item) // break here: Step Into, then look at `discount` per item
        def price = Pricing.apply(discount, item.price)
        def subtotal = price * item.quantity
        lines << String.format('%-4s %-9s %3d x %5.2f = %7.2f', item.sku, item.name, item.quantity, price, subtotal)
        byPrefix[item.sku[0]] = (byPrefix[item.sku[0]] ?: 0) + item.quantity
        total += subtotal // break here: a watch on `total`, or hover `item.name`
    }
    def cheap = shelf.findAll { it.price < 1.0 } // break here: inside a closure (the frame reads `shelf.checkout { closure }`)
            .collect { it.name }
            .sort()
    lines << "cheap: ${cheap.join(', ')}  by prefix: $byPrefix"
    [lines, total]
}

/** Turns "12" into 12; a word is a caught NumberFormatException, and null. */
Integer parseQuantity(String text) {
    try {
        Integer.parseInt(text)
    } catch (NumberFormatException e) {
        println "not a quantity: $text" // break here: `e` in Variables, or Break on Caught Exceptions
        null
    }
}

def limit = 6 // break here: a first plain breakpoint (the frame reads `shelf.run`); Step Over, then Step Into checkout
if (args && args[0] == 'crash') {
    throw new IllegalStateException('crash requested') // uncaught: the debugger stops here by itself
} else if (args) {
    limit = parseQuantity(args[0]) ?: limit
}
def (lines, total) = checkout(items, limit)
lines.each { println it }
println String.format('total %.2f', total)
println "restocked ${Restock.run(3)}"
