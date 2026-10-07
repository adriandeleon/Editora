package com.example;

/** Trivial entry point so the Maven sample looks like a real project. */
public class App {
    public static void main(String[] args) {
        System.out.println(greeting(args.length > 0 ? args[0] : "the Maven sample project"));
    }

    /** The line {@link #main} prints; {@code AppTest} checks it. */
    static String greeting(String who) {
        if (who == null || who.isBlank()) {
            throw new IllegalArgumentException("who must not be blank");
        }
        return "Hello from " + who + ".";
    }
}
