package main

import "testing"

// Put the caret in a test and use Debug Test at Caret (or F5): only that test runs, under Delve.
func TestCheckoutTotals(t *testing.T) {
	lines, total := checkout(items, 2)
	if len(lines) != 3 { // break here: `lines` and `total` in Variables
		t.Fatalf("lines = %d, want 3", len(lines))
	}
	if total < 12 || total > 13 {
		t.Fatalf("total = %.2f", total)
	}
}

func TestStackedDiscount(t *testing.T) {
	got := applyDiscount(&Discount{Percent: 10, Then: &Discount{Percent: 50}}, 100)
	if got != 45 {
		t.Fatalf("got %v, want 45", got)
	}
}

func TestParseQuantity(t *testing.T) {
	if n, err := parseQuantity("12"); err != nil || n != 12 {
		t.Fatalf("parseQuantity(12) = %d, %v", n, err)
	}
	if _, err := parseQuantity("many"); err == nil {
		t.Fatal("a word is not a quantity")
	}
}

func TestRestock(t *testing.T) {
	if got := restock(3); got != 480 {
		t.Fatalf("restocked %d, want 480", got)
	}
}

func BenchmarkCheckout(b *testing.B) {
	for range b.N {
		checkout(items, len(items))
	}
}
