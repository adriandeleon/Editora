package com.example.shelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** All green. Use "Debug Test" on one of these to hit a breakpoint inside the code under test. */
class ShelfTest {

    @Test
    void checkoutTotalsEveryItem() {
        Shelf.Receipt receipt = Shelf.sample().checkout(Integer.MAX_VALUE);
        assertEquals(9, receipt.lines().size()); // 8 items + the "cheap:" summary line
        assertEquals(56.48, receipt.total(), 0.001); // break here, then Debug Test: step into checkout
    }

    @Test
    void checkoutStopsAtTheLimit() {
        Shelf.Receipt receipt = Shelf.sample().checkout(2);
        assertEquals(3, receipt.lines().size());
    }

    @ParameterizedTest
    @CsvSource({"A-1, 12, 0.40, 0.38", "B-1, 1, 8.90, 8.65", "B-2, 4, 1.20, 0.58", "A-2, 3, 2.75, 2.75"})
    void discountsFollowSkuAndQuantity(String sku, int quantity, double price, double expected) {
        Item item = new Item(sku, "x", quantity, price);
        assertEquals(expected, Pricing.apply(Pricing.discountFor(item), price), 0.001);
    }

    @Test
    void stackedDiscountAppliesBothInOrder() {
        var stacked = new Discount.Stacked(new Discount.Percent(50), new Discount.Flat(1.0));
        assertEquals(4.0, Pricing.apply(stacked, 10.0), 0.001);
    }

    @Test
    void parseQuantityFallsBackOnWords() {
        assertEquals(7, Shelf.parseQuantity("seven"));
        assertEquals(3, Shelf.parseQuantity("3"));
    }

    @Test
    void negativeQuantityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Item("Z-1", "Bad", -1, 1.0));
    }
}
