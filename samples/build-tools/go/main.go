// Sample Go module for exercising Editora's Go build-tool support.
// The actions popup shows the standard subcommands (build/run/test/vet/fmt/
// mod tidy/…) over the whole module. Standalone — the repo's own build ignores it.
package main

import "fmt"

func main() {
	fmt.Println("Hello from the Go sample project.")
}
