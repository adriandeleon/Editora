package com.example.shelf;

/** One shelf item. A record, so the Variables view shows its components, not fields. */
record Item(String sku, String name, int quantity, double price) {

    /** Compact constructor: a breakpoint here fires once per item built in {@link Shelf#sample()}. */
    Item {
        if (quantity < 0) {
            throw new IllegalArgumentException("negative quantity for " + sku);
        }
        name = name.strip();
    }

    double subtotal() {
        return quantity * price;
    }
}
