// Sample Go module for exercising Editora's Go build-tool support.
// The actions popup shows the standard subcommands (build/run/test/vet/fmt/
// mod tidy/…) over the whole module. Standalone — the repo's own build ignores it.
package main

import "fmt"

// greeting is the line main prints; main_test.go checks it.
func greeting(who string) string {
	return "Hello from " + who + "."
}

func main() {
	fmt.Println(greeting("the Go sample project"))
}
