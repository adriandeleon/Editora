package main

import "fmt"

type Point struct{ X, Y int }

func (p Point) Norm2() int { return p.X*p.X + p.Y*p.Y }

func main() {
	fmt.Println(Point{3, 4}.Norm2())
}
