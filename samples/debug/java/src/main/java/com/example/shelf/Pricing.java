package com.example.shelf;

/** Applies discounts with a pattern-matching switch over the sealed {@link Discount}. */
final class Pricing {

    private Pricing() {}

    /** Picks the discount for an item: bulk items get a percentage, "B-" items a flat cut, one gets both. */
    static Discount discountFor(Item item) {
        if (item.sku().equals("B-2")) { // break here: make the breakpoint conditional on item.sku().equals("B-2")
            return new Discount.Stacked(new Discount.Percent(10), new Discount.Flat(0.50));
        }
        if (item.sku().startsWith("B-")) {
            return new Discount.Flat(0.25);
        }
        if (item.quantity() >= 10) {
            return new Discount.Percent(5);
        }
        return new Discount.None();
    }

    /** Record patterns bind the components directly; {@code Stacked} recurses twice. */
    static double apply(Discount discount, double price) {
        return switch (discount) { // break here: step over to see which arm runs
            case Discount.None() -> price;
            case Discount.Percent(int percent) -> price * (100 - percent) / 100.0;
            case Discount.Flat(double amount) -> Math.max(0, price - amount);
            case Discount.Stacked(var first, var then) -> apply(then, apply(first, price)); // recursion
        };
    }
}
