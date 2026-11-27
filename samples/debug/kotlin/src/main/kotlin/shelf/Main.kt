// The shelf, in Kotlin: the entry point. Debug it with Debug: Debug via Build Tool (Gradle's `run` under a
// suspended JVM, which Editora's Kotlin debugger attaches to). F5 here compiles this one file on its own, which
// can't see the rest of the project. Every suggested spot is a "break here" comment.
package shelf

fun main(args: Array<String>) {
    var limit = 6 // break here: a first plain breakpoint; Step Over, then Step Into checkout
    val arg = args.firstOrNull()
    if (arg == "crash") {
        throw IllegalStateException("crash requested") // uncaught: the debugger stops here by itself
    } else if (arg != null) {
        limit = parseQuantity(arg) ?: limit
    }
    val receipt = checkout(SHELF, limit)
    receipt.lines.forEach(::println)
    println("total %.2f".format(receipt.total))
    println("restocked ${restock(3)}")
}

/** Turns "12" into 12; a word is a caught NumberFormatException, and null. */
fun parseQuantity(text: String): Int? =
    try {
        text.toInt()
    } catch (e: NumberFormatException) {
        println("not a quantity: $text") // break here: `e` in Variables, or Break on Caught Exceptions
        null
    }
