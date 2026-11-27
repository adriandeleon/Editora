// A Kotlin script: the ▶ on the first line runs it with `kotlinc -script`, from the top (no `main`, no build).
// Needs kotlinc (KOTLIN_HOME, PATH or SDKMAN). F5 runs it under the Kotlin debugger.
val names = listOf("Pencil", "Notebook", "Stapler")
println("Hello from a Kotlin script!")
for ((i, name) in names.withIndex()) {
    println("line ${i + 1}: $name") // break here: `i` and `name` in Variables
}
println("args: ${args.joinToString()}")
