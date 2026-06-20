data class Point(val x: Int, val y: Int) {
    fun norm2(): Int = x * x + y * y
}

fun main() {
    println(Point(3, 4).norm2())
}
