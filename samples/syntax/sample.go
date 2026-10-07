// Package main is a small Go sample for syntax highlighting and folding.
//
// It covers structs, methods, an interface, generics, a goroutine with a
// channel, defer, error wrapping, and raw strings.
package main

import (
	"errors"
	"fmt"
	"strings"
)

const maxItems = 0x40 // hex literal

// ErrShelfFull is returned once the shelf holds maxItems.
var ErrShelfFull = errors.New("shelf is full")

// Item is anything that can describe itself.
type Item interface {
	Describe() string
}

type Book struct {
	Title string
	Pages int
}

func (b Book) Describe() string {
	return fmt.Sprintf("a book: %q (%d pages)", b.Title, b.Pages)
}

type Shelf struct {
	items []Item
}

func (s *Shelf) Add(item Item) error {
	if len(s.items) >= maxItems {
		return fmt.Errorf("adding %s: %w", item.Describe(), ErrShelfFull)
	}
	s.items = append(s.items, item)
	return nil
}

// Map applies f to every element (a generic function).
func Map[T, U any](in []T, f func(T) U) []U {
	out := make([]U, 0, len(in))
	for _, v := range in {
		out = append(out, f(v))
	}
	return out
}

func main() {
	shelf := &Shelf{}
	defer fmt.Println("done")

	/* A block comment, then a raw string. */
	banner := `Inventory
---------`
	if err := shelf.Add(Book{Title: "Dune", Pages: 612}); err != nil {
		fmt.Println("error:", err)
		return
	}

	lines := make(chan string)
	go func() {
		defer close(lines)
		for _, d := range Map(shelf.items, Item.Describe) {
			lines <- strings.ToUpper(d[:1]) + d[1:]
		}
	}()
	fmt.Println(banner)
	for line := range lines {
		fmt.Println("-", line)
	}
}
