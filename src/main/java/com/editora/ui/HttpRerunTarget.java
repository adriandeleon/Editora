package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.editora.http.HttpFile;

/**
 * Which request of the file the response view's Run button re-sends for a history entry. The entry knows the
 * request's name and the method and URL it sent (variables filled in); its position in the file is only a
 * tie-breaker, because requests move: inserting one above used to make Run send a different request — a DELETE
 * just as easily as the GET the entry showed.
 *
 * <p>The order: the request with the same name; else the one with the same method whose URL template matches the
 * URL that was sent (each {@code {{…}}} matches any text, so a changed environment still finds it); else none,
 * and the caller runs the request at the caret and says so. Pure — tested by {@code HttpRerunTargetTest}.
 */
final class HttpRerunTarget {

    private HttpRerunTarget() {}

    /**
     * The index in {@code requests} of the request {@code name} / {@code method} {@code sentUrl} came from, or -1.
     * {@code formerIndex} (the entry's old position, -1 unknown) only picks between equally good matches.
     */
    static int find(List<HttpFile.Request> requests, String name, String method, String sentUrl, int formerIndex) {
        if (requests == null || requests.isEmpty()) {
            return -1;
        }
        if (name != null && !name.isBlank()) {
            List<Integer> named = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                if (name.strip().equals(nameOf(requests.get(i)))) {
                    named.add(i);
                }
            }
            if (named.size() == 1) {
                return named.get(0);
            }
            if (named.size() > 1) {
                List<Integer> both = matching(requests, named, method, sentUrl);
                return nearest(both.isEmpty() ? named : both, formerIndex);
            }
        }
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            all.add(i);
        }
        List<Integer> same = matching(requests, all, method, sentUrl);
        return same.isEmpty() ? -1 : nearest(same, formerIndex);
    }

    private static String nameOf(HttpFile.Request r) {
        return r.name() == null || r.name().isBlank() ? null : r.name().strip();
    }

    /** The candidates with {@code method} whose URL template could have produced {@code sentUrl}. */
    private static List<Integer> matching(
            List<HttpFile.Request> requests, List<Integer> candidates, String method, String sentUrl) {
        List<Integer> out = new ArrayList<>();
        if (method == null || sentUrl == null) {
            return out;
        }
        for (int i : candidates) {
            HttpFile.Request r = requests.get(i);
            if (method.equalsIgnoreCase(r.method()) && templateMatches(r.url(), sentUrl)) {
                out.add(i);
            }
        }
        return out;
    }

    /** Whether {@code template} (with {@code {{…}}} references) can expand to {@code url}. */
    static boolean templateMatches(String template, String url) {
        if (template == null || url == null) {
            return false;
        }
        String t = template.strip();
        String u = url.strip();
        if (t.equals(u)) {
            return true;
        }
        StringBuilder regex = new StringBuilder();
        int at = 0;
        while (at < t.length()) {
            int open = t.indexOf("{{", at);
            int close = open < 0 ? -1 : t.indexOf("}}", open + 2);
            if (open < 0 || close < 0) {
                regex.append(Pattern.quote(t.substring(at)));
                break;
            }
            if (open > at) {
                regex.append(Pattern.quote(t.substring(at, open)));
            }
            regex.append(".*?");
            at = close + 2;
        }
        try {
            return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(u).matches();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int nearest(List<Integer> candidates, int formerIndex) {
        if (formerIndex < 0) {
            return candidates.get(0);
        }
        int best = candidates.get(0);
        for (int i : candidates) {
            if (Math.abs(i - formerIndex) < Math.abs(best - formerIndex)) {
                best = i;
            }
        }
        return best;
    }
}
