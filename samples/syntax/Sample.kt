/**
 * A small Kotlin sample for syntax highlighting and folding.
 *
 * Covers KDoc, a sealed interface, data classes, an extension function, a
 * `when` expression, null safety, lambdas, string templates, and an object.
 */
package demo

const val MAX_ITEMS = 0x40 // hex literal

sealed interface Item {
    val name: String
}

data class Book(override val name: String, val pages: Int) : Item

data class Tool(override val name: String, val weightKg: Double) : Item

class ShelfFull(message: String) : Exception(message)

class Shelf {
    private val items = mutableListOf<Item>()

    val size: Int
        get() = items.size

    fun add(item: Item) {
        if (items.size >= MAX_ITEMS) {
            throw ShelfFull("no room for ${item.name}")
        }
        items += item
    }

    fun find(name: String): Item? = items.firstOrNull { it.name == name }

    fun names(): List<String> = items.map { it.name }.sorted()
}

/* A block comment: `when` is exhaustive because Item is sealed. */
fun Item.describe(): String = when (this) {
    is Book -> if (pages > 500) "a long book: \"$name\"" else "a book: $name"
    is Tool -> "a tool (%.1f kg): %s".format(weightKg, name)
}

object Banner {
    val text = """
        |Inventory
        |---------
    """.trimMargin()
}

fun main() {
    val shelf = Shelf()
    try {
        shelf.add(Book("Dune", 612))
        shelf.add(Tool("Hammer", 0.6))
    } catch (e: ShelfFull) {
        println("error: ${e.message}")
    }
    println(Banner.text)
    for ((index, name) in shelf.names().withIndex()) {
        println("${index + 1}. $name")
    }
    println(shelf.find("Dune")?.describe() ?: "not found")
}
