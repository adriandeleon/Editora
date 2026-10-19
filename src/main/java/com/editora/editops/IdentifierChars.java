package com.editora.editops;

/**
 * Which characters a language's identifiers are made of beyond letters, digits and {@code _}. In
 * JavaScript and TypeScript {@code $} is an ordinary identifier character ({@code $el}, {@code user$},
 * jQuery's {@code $}) and {@code #} starts a private name ({@code #count}); treating them as separators made a
 * double-click on {@code $el} select {@code el}, and Select Next Occurrence and Go to Definition look for
 * {@code el}. Pure.
 */
public final class IdentifierChars {

    private IdentifierChars() {}

    /** Whether {@code $} is part of {@code language}'s identifiers (and {@code #} may start one). */
    public static boolean dollarIdentifiers(String language) {
        return language != null && (language.startsWith("javascript") || language.startsWith("typescript"));
    }

    /** A letter, digit or {@code _}, and {@code $} when {@code dollar}. */
    public static boolean isPart(char c, boolean dollar) {
        return Character.isLetterOrDigit(c) || c == '_' || (dollar && c == '$');
    }
}
