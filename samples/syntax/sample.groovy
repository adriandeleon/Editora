class Point {
    int x, y
    int norm2() { x * x + y * y }
}

def p = new Point(x: 3, y: 4)
println p.norm2()
