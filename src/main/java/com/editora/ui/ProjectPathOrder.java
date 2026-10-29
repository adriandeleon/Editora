package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared ordering contract for project navigation (the Project tree and the Project Map): folders first, then
 * names in natural order without case bias.
 */
final class ProjectPathOrder {

    private ProjectPathOrder() {}

    /**
     * Folders first, then names in {@link #NATURAL} order. Names that differ only by case ({@code README} and
     * {@code readme} on a case-sensitive filesystem) or by leading zeros ({@code a01}, {@code a1}) fall back
     * to exact order, so the result never depends on the order a directory happened to be listed in.
     */
    static <T> Comparator<T> directoriesFirst(Predicate<T> isDirectory, Function<T, String> name) {
        return Comparator.<T, Boolean>comparing(item -> !isDirectory.test(item))
                .thenComparing(name, NATURAL)
                .thenComparing(name);
    }

    /**
     * Names without case bias, with every run of ASCII digits compared by its value: {@code note-2} before
     * {@code note-10}, as a file manager lists numbered chapters, migrations and decision records. Everything
     * else compares character by character as {@link String#CASE_INSENSITIVE_ORDER} does. Runs that only
     * differ in leading zeros compare equal here; {@link #directoriesFirst} breaks that tie by exact order.
     */
    static final Comparator<String> NATURAL = ProjectPathOrder::compareNatural;

    private static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;
        int lengthA = a.length();
        int lengthB = b.length();
        while (i < lengthA && j < lengthB) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (isDigit(ca) && isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < lengthA && isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < lengthB && isDigit(b.charAt(j))) {
                    j++;
                }
                int byValue = compareDigits(a, startA, i, b, startB, j);
                if (byValue != 0) {
                    return byValue;
                }
                continue;
            }
            if (ca != cb) {
                char fa = Character.toLowerCase(Character.toUpperCase(ca));
                char fb = Character.toLowerCase(Character.toUpperCase(cb));
                if (fa != fb) {
                    return fa - fb;
                }
            }
            i++;
            j++;
        }
        return (lengthA - i) - (lengthB - j);
    }

    /** Compares two digit runs by value: leading zeros skipped, then the longer run is the bigger number. */
    private static int compareDigits(String a, int startA, int endA, String b, int startB, int endB) {
        while (startA < endA - 1 && a.charAt(startA) == '0') {
            startA++;
        }
        while (startB < endB - 1 && b.charAt(startB) == '0') {
            startB++;
        }
        int lengths = (endA - startA) - (endB - startB);
        if (lengths != 0) {
            return lengths;
        }
        for (int index = 0; index < endA - startA; index++) {
            int digits = a.charAt(startA + index) - b.charAt(startB + index);
            if (digits != 0) {
                return digits;
            }
        }
        return 0;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * {@code paths} in the shared order, asking {@code isDirectory} exactly once per path. The question is a
     * {@code stat}; asked from inside a comparator it is put twice per comparison, some {@code 2·n·log2 n}
     * times for one listing, which is what a large folder on a slow mount was paying to be sorted.
     */
    static List<Path> sorted(Collection<Path> paths, Predicate<Path> isDirectory) {
        record Keyed(Path path, boolean directory, String name) {}
        List<Keyed> keyed = new ArrayList<>(paths.size());
        for (Path path : paths) {
            Path name = path.getFileName();
            keyed.add(new Keyed(path, isDirectory.test(path), name == null ? path.toString() : name.toString()));
        }
        keyed.sort(directoriesFirst(Keyed::directory, Keyed::name));
        List<Path> out = new ArrayList<>(keyed.size());
        for (Keyed k : keyed) {
            out.add(k.path());
        }
        return out;
    }
}
