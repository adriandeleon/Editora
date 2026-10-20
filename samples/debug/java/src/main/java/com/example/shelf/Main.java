package com.example.shelf;

import java.util.List;

/**
 * Entry point of the debugging demo. Run it with no arguments, or with one argument:
 * a number (used as a quantity), any other word (a caught {@code NumberFormatException}),
 * or {@code crash} (ends with an uncaught exception, exit code 1).
 *
 * <p>Lines marked {@code break here} are the suggested breakpoints; each one exercises a
 * different debugger feature.
 */
public final class Main {

    /** A static field to change with set-value while paused (try 3, then watch the receipt shrink). */
    static int limit = 12;

    public static void main(String[] args) throws InterruptedException {
        var shelf = Shelf.sample();
        System.out.println(greeting()); // break here: then edit greeting() and save (hot code replace), step over

        var receipt = shelf.checkout(limit); // break here: step into checkout, or set-value on `limit` first
        receipt.lines().forEach(System.out::println);
        System.out.printf("total %.2f%n", receipt.total());

        var restocked = Restocker.restock(shelf.items(), 3); // worker threads live in here
        System.out.println("restocked " + restocked);

        int quantity = Shelf.parseQuantity(args.length > 0 ? args[0] : "seven"); // "seven" is not a number
        System.out.println("quantity " + quantity);

        if (List.of(args).contains("crash")) {
            throw new IllegalStateException("crash requested"); // uncaught: an exception breakpoint stops here
        }
    }

    private Main() {}

    static String greeting() {
        return "Shelf demo"; // hot code replace: change this text while paused in main, save, resume
    }
}
