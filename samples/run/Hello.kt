// A lone Kotlin program (a top-level `fun main`, no build): the ▶ on the `fun main(` line compiles it with kotlinc
// into a cached jar and runs it with `java -jar`; F5 does the same under the Kotlin debugger. Needs kotlinc
// (KOTLIN_HOME, PATH or SDKMAN). Lines marked "break here" are worth a breakpoint.
fun main(args: Array<String>) {
    val who = args.firstOrNull() ?: "Editora" // break here: a first breakpoint (the frame reads `Hello.main`)
    println("Hello from a Kotlin program, $who!")
    val square = { n: Int ->
        n * n // break here: inside a lambda (the frame reads `Hello.main { lambda }`), once per n
    }
    (1..3).map(square).forEachIndexed { i, square -> println("line ${i + 1}: $square") }
}
