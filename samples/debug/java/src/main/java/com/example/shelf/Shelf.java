package com.example.shelf;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** The shelf: a list of items, a checkout loop, and a parse with a caught exception. */
final class Shelf {

    /** What checkout produces: the printed lines and the grand total. */
    record Receipt(List<String> lines, double total) {}

    private final List<Item> items;

    private Shelf(List<Item> items) {
        this.items = List.copyOf(items);
    }

    static Shelf sample() {
        return new Shelf(List.of(
                new Item("A-1", "Pencil", 12, 0.40),
                new Item("A-2", "Notebook", 3, 2.75),
                new Item("B-1", "Stapler ", 1, 8.90),
                new Item("B-2", "Tape", 4, 1.20),
                new Item("C-1", "Marker", 10, 1.05),
                new Item("C-2", "Eraser", 25, 0.30),
                new Item("C-3", "Ruler", 2, 1.50),
                new Item("D-1", "Glue", 6, 2.10)));
    }

    List<Item> items() {
        return items;
    }

    /**
     * Totals up to {@code limit} items. Worth a watch on {@code total}, a logpoint printing
     * {@code item.name()} and {@code subtotal}, and a look at {@code byPrefix} growing.
     */
    Receipt checkout(int limit) {
        var lines = new ArrayList<String>();
        var byPrefix = new TreeMap<String, Integer>();
        double total = 0;
        int n = 0;
        for (Item item : items) {
            if (++n > limit) {
                break;
            }
            Discount discount = Pricing.discountFor(item); // break here: step into, inspect `discount` per item
            double price = Pricing.apply(discount, item.price());
            double subtotal = price * item.quantity();
            byPrefix.merge(item.sku().substring(0, 1), item.quantity(), Integer::sum);
            total += subtotal; // logpoint here: "{item.name()} -> {subtotal}"
            lines.add("%-4s %-9s %3d x %5.2f = %7.2f".formatted(item.sku(), item.name(), item.quantity(), price, subtotal));
        }
        var cheap = items.stream()
                .filter(item -> item.price() < 1.0) // break here: a breakpoint inside a lambda
                .map(Item::name)
                .sorted()
                .toList();
        lines.add("cheap: " + cheap + "  by prefix: " + byPrefix);
        return new Receipt(List.copyOf(lines), total);
    }

    /** Parses a quantity, falling back to 7. The caught exception is a good "exception breakpoint" test. */
    static int parseQuantity(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException err) {
            System.out.println("not a number: " + err.getMessage()); // break here: `err` is in Variables
            return 7;
        }
    }
}
