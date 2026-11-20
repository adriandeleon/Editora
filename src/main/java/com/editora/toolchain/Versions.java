package com.editora.toolchain;

import java.util.ArrayList;
import java.util.List;

/** Comparing and matching tool versions as jkit, SDKMAN and {@code release} files write them. Pure. */
public final class Versions {

    private Versions() {}

    /**
     * A sort key: the release's numbers, then whether it is a pre-release ({@code -ea}, {@code -rc1}, {@code -M2},
     * which sorts before its release), then the pre-release's own numbers ({@code 26-ea+3} before {@code 26-ea+5}).
     */
    public record Key(List<Integer> numbers, boolean preRelease, List<Integer> preNumbers) implements Comparable<Key> {
        @Override
        public int compareTo(Key o) {
            int c = compare(numbers, o.numbers);
            if (c != 0) {
                return c;
            }
            if (preRelease != o.preRelease) {
                return preRelease ? -1 : 1;
            }
            return compare(preNumbers, o.preNumbers);
        }

        private static int compare(List<Integer> a, List<Integer> b) {
            int n = Math.max(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                int x = i < a.size() ? a.get(i) : 0;
                int y = i < b.size() ? b.get(i) : 0;
                if (x != y) {
                    return Integer.compare(x, y);
                }
            }
            return 0;
        }
    }

    public static Key key(String version) {
        List<Integer> numbers = new ArrayList<>();
        List<Integer> preNumbers = new ArrayList<>();
        int i = 0;
        String v = version.strip();
        boolean pre = false;
        while (i < v.length()) {
            char c = v.charAt(i);
            if (Character.isDigit(c)) {
                int start = i;
                while (i < v.length() && Character.isDigit(v.charAt(i))) {
                    i++;
                }
                int n;
                try {
                    n = Integer.parseInt(v.substring(start, Math.min(i, start + 9)));
                } catch (NumberFormatException e) {
                    n = 0;
                }
                (pre ? preNumbers : numbers).add(n);
            } else {
                if (c == '-' && i + 1 < v.length() && Character.isLetter(v.charAt(i + 1))) {
                    pre = true; // 26-ea, 4.0.0-rc-1, 9.0-milestone-3
                }
                i++;
            }
        }
        return new Key(List.copyOf(numbers), pre, List.copyOf(preNumbers));
    }

    /**
     * Whether installed {@code version} answers a request for {@code wanted}, a version or a prefix of one:
     * {@code 21} and {@code 21.0} match {@code 21.0.9+10}, {@code 21.0.9} matches {@code 21.0.9+10}, and a request
     * with a build ({@code 21.0.9+10}) matches a version written without one ({@code 21.0.9}, as SDKMAN and
     * {@code release} files write it). {@code 21.0.1} does not match {@code 21.0.10}.
     */
    public static boolean matches(String version, String wanted) {
        String v = version.strip();
        String w = wanted.strip();
        if (w.isEmpty()) {
            return true;
        }
        if (v.equals(w)) {
            return true;
        }
        if (v.startsWith(w) && boundary(v.charAt(w.length()))) {
            return true;
        }
        int plus = w.indexOf('+');
        if (plus > 0 && v.indexOf('+') < 0) {
            return matches(v, w.substring(0, plus));
        }
        return false;
    }

    private static boolean boundary(char c) {
        return c == '.' || c == '+' || c == '_' || c == '-';
    }
}
