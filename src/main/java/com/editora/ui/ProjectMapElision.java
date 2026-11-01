package com.editora.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * How a Project Map row's name too long for its row is cut so that siblings stay apart (review 3 B10). Long Java and
 * Spring names share their start and their end with the names beside them ({@code AbstractRefreshable…
 * ApplicationContext.java}), and a cut in the middle kept exactly the parts they share, drawing two files alike. The
 * cut drops what a name shares with its siblings first and keeps the part that tells it apart, with its extension.
 * Pure.
 */
final class ProjectMapElision {

    /** The most of a shared start kept before the distinct part, when there is room for it. */
    private static final int HEAD = 3;
    /** The most of a name's end kept as its extension. */
    private static final int EXTENSION = 6;

    private ProjectMapElision() {}

    /**
     * For each of {@code names}, {@code {prefix, suffix}}: the longest start and the longest end it shares with any
     * other of them (in characters). Sorted neighbours have the longest common start, so this is {@code n log n}.
     */
    static Map<String, int[]> sharedAffixes(List<String> names) {
        Map<String, int[]> shared = new HashMap<>();
        List<String> distinct = names.stream().distinct().toList();
        for (String name : distinct) {
            shared.put(name, new int[2]);
        }
        List<String> forward = new ArrayList<>(distinct);
        forward.sort(Comparator.naturalOrder());
        for (int i = 1; i < forward.size(); i++) {
            int common = commonPrefix(forward.get(i - 1), forward.get(i));
            raise(shared, forward.get(i - 1), 0, common);
            raise(shared, forward.get(i), 0, common);
        }
        List<String> backward = new ArrayList<>(distinct);
        backward.sort(Comparator.comparing(ProjectMapElision::reversed));
        for (int i = 1; i < backward.size(); i++) {
            int common = commonSuffix(backward.get(i - 1), backward.get(i));
            raise(shared, backward.get(i - 1), 1, common);
            raise(shared, backward.get(i), 1, common);
        }
        return shared;
    }

    /**
     * {@code text} cut to {@code kept} characters (and up to two ellipses) by dropping first what it shares with its
     * siblings — its first {@code prefix} and last {@code suffix} characters — around the part between them, which is
     * kept whole with its extension, a few of its first characters, and as much context around it as fits. Null when
     * that part and the extension alone do not fit: the caller cuts in the middle instead.
     */
    static @Nullable String distinctCut(String text, int prefix, int suffix, int kept) {
        int length = text.length();
        int p = Math.max(0, Math.min(prefix, length));
        int s = Math.max(0, Math.min(suffix, length - p));
        if (p + s == 0 || kept >= length) {
            return kept >= length ? text : null;
        }
        int dot = text.lastIndexOf('.');
        int extension = dot > 0 && length - dot <= EXTENSION ? length - dot : 0;
        int tail = Math.min(extension, s);
        int core = length - s - p;
        int remaining = kept - tail - core;
        if (remaining < 0 || core == 0 && remaining < 2) {
            return null;
        }
        int head = remaining >= HEAD * 3 ? Math.min(HEAD, p) : 0;
        remaining -= head;
        int left = Math.min(p - head, remaining / 2);
        int right = Math.min(s - tail, remaining - left);
        left = Math.min(p - head, remaining - right); // what the right side could not use goes left
        int from = p - left;
        int to = length - s + right;
        StringBuilder out = new StringBuilder(kept + 2);
        out.append(text, 0, head);
        if (from > head) {
            out.append('…');
        }
        out.append(text, from, to);
        if (to < length - tail) {
            out.append('…');
        }
        out.append(text, length - tail, length);
        return safe(out.toString(), text);
    }

    /** Never a half of a surrogate pair next to an ellipsis: the plain middle cut is used instead. */
    private static @Nullable String safe(String cut, String text) {
        for (int i = 0; i < cut.length(); i++) {
            char c = cut.charAt(i);
            boolean high = Character.isHighSurrogate(c);
            boolean low = Character.isLowSurrogate(c);
            if (high && (i + 1 >= cut.length() || !Character.isLowSurrogate(cut.charAt(i + 1)))
                    || low && (i == 0 || !Character.isHighSurrogate(cut.charAt(i - 1)))) {
                return null;
            }
        }
        return cut.equals(text) ? text : cut;
    }

    private static void raise(Map<String, int[]> shared, String name, int index, int value) {
        int[] affixes = shared.get(name);
        if (affixes != null && value > affixes[index]) {
            affixes[index] = value;
        }
    }

    private static int commonPrefix(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        int i = 0;
        while (i < limit && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i == Math.max(a.length(), b.length()) ? 0 : i; // the same name twice shares nothing useful
    }

    private static int commonSuffix(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        int i = 0;
        while (i < limit && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i == Math.max(a.length(), b.length()) ? 0 : i;
    }

    private static String reversed(String value) {
        return new StringBuilder(value).reverse().toString();
    }
}
