package com.example.shelf;

/**
 * A sealed hierarchy of discounts. {@link Stacked} nests discounts, so applying one is
 * recursive — pause inside {@link Pricing#apply} and look at the call stack.
 */
sealed interface Discount {

    /** No discount. */
    record None() implements Discount {}

    /** A percentage off, 0–100. */
    record Percent(int percent) implements Discount {}

    /** A fixed amount off, never below zero. */
    record Flat(double amount) implements Discount {}

    /** {@code first} applied, then {@code then} applied to the result. */
    record Stacked(Discount first, Discount then) implements Discount {}
}
