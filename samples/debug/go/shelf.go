package main

import (
	"fmt"
	"sort"
	"strconv"
)

// Item is what is on the shelf; expand one in Variables to see its fields.
type Item struct {
	SKU      string
	Name     string
	Quantity int
	Price    float64
}

var items = []Item{
	{"A-1", "Pencil", 12, 0.40},
	{"A-2", "Notebook", 3, 2.75},
	{"B-1", "Stapler", 1, 8.90},
	{"B-2", "Tape", 4, 1.20},
	{"C-1", "Marker", 10, 1.05},
	{"C-2", "Eraser", 25, 0.30},
	{"C-3", "Ruler", 2, 1.50},
	{"D-1", "Glue", 6, 2.10},
}

// checkout totals up to limit items: `total`, `lines` and `byPrefix` change on every pass.
func checkout(shelf []Item, limit int) ([]string, float64) {
	var lines []string
	byPrefix := map[string]int{}
	total := 0.0
	for i, item := range shelf {
		if i == limit {
			break
		}
		discount := discountFor(item) // break here: Step Into, then look at `discount` per item
		price := applyDiscount(discount, item.Price)
		subtotal := price * float64(item.Quantity)
		lines = append(lines, fmt.Sprintf("%-4s %-9s %3d x %5.2f = %7.2f", item.SKU, item.Name, item.Quantity, price, subtotal))
		byPrefix[item.SKU[:1]] += item.Quantity
		total += subtotal // break here: a watch on `total`, or a logpoint printing {item.Name}
	}
	var cheap []string
	for _, item := range shelf {
		if item.Price < 1.0 { // break here: a conditional breakpoint, item.SKU == "C-2"
			cheap = append(cheap, item.Name)
		}
	}
	sort.Strings(cheap)
	lines = append(lines, fmt.Sprintf("cheap: %v  by prefix: %v", cheap, byPrefix))
	return lines, total
}

// parseQuantity turns "12" into 12; a word is an error, returned rather than panicking.
func parseQuantity(text string) (int, error) {
	n, err := strconv.Atoi(text)
	if err != nil {
		return 0, fmt.Errorf("not a quantity: %q: %w", text, err) // break here: `err` in Variables
	}
	return n, nil
}
