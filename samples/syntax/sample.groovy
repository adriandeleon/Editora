/**
 * A small Groovy sample for syntax highlighting and folding.
 *
 * Covers Groovydoc, a class with properties, closures, GStrings, list and map
 * literals, the safe-navigation and Elvis operators, ranges, and a regex.
 */
package demo

import groovy.transform.ToString

@ToString(includeNames = true)
class Item {
    String name
    BigDecimal weightKg = 0.0

    String describe() {
        weightKg > 10 ? "heavy: ${name}" : "light: $name"
    }
}

class Shelf {
    static final int MAX_ITEMS = 0x40 // hex literal
    private final List<Item> items = []

    Shelf leftShift(Item item) {
        if (items.size() >= MAX_ITEMS) {
            throw new IllegalStateException('The shelf is full.')
        }
        items << item
        this
    }

    def each(Closure body) {
        items.each(body)
    }

    Map<String, List<Item>> byInitial() {
        items.groupBy { it.name[0].toUpperCase() }
    }
}

/* A block comment, then a multi-line string. */
def banner = '''\
Inventory
---------'''

def shelf = new Shelf()
shelf << new Item(name: 'hammer', weightKg: 0.6) << new Item(name: 'anvil', weightKg: 45)

println banner
shelf.each { item -> println "- ${item.describe()}" }
shelf.byInitial().each { initial, group ->
    println "$initial: ${group*.name.join(', ')}"
}

def missing = shelf.byInitial()['Z']?.first()?.name ?: 'nothing under Z'
assert missing == 'nothing under Z'

(1..3).each { n -> print "${n * n} " }
println()
println(('hammer' =~ /m+/).find() ? 'has an m' : 'no m')
