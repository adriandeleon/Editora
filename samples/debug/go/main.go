// The shelf, in Go: open this file and press F5 (Debug) to debug the package.
// Every suggested spot is a "break here" comment. `go run .` runs it outside Editora, `go test` runs the tests.
package main

import (
	"fmt"
	"os"
	"strconv"
)

func main() {
	limit := 6 // break here: a first plain breakpoint; Step Over, then Step Into checkout
	if len(os.Args) > 1 {
		if os.Args[1] == "crash" {
			var missing map[string]int
			missing["boom"] = 1 // a panic: Delve stops here by itself
		}
		if n, err := strconv.Atoi(os.Args[1]); err == nil {
			limit = n
		}
	}
	lines, total := checkout(items, limit)
	for _, line := range lines {
		fmt.Println(line)
	}
	fmt.Printf("total %.2f\n", total)
	fmt.Println("restocked", restock(3))
}
