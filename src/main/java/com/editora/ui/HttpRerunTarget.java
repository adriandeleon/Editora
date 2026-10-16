package com.editora.ui;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;

import com.editora.http.HttpFile;
import com.editora.http.HttpVars;

/**
 * Which request of the file the response view's Run button re-sends for a history entry. Requests move, get
 * renamed and get edited, so the entry's old position is only a tie-breaker: inserting a request above used to make
 * Run send a different one — a DELETE just as easily as the GET the entry showed.
 *
 * <p>The order:
 * <ul>
 *   <li>A <b>named</b> entry: the request with that name. If several have it, the one whose text is unchanged (its
 *       fingerprint), else whose URL is as written, else whose URL template matches what was sent; then the nearest.
 *       If none has the name any more, none — a named entry never falls back to a request with another name.
 *   <li>An <b>unnamed</b> entry, with the same method: the request whose text is unchanged, else whose URL is as the
 *       run had it written. An entry saved before those were kept matches by URL template against the URL that was
 *       sent (each {@code {{…}}} stands for any text, one path segment between {@code /}s).
 *   <li>Else none: the caller runs the request at the caret and says so.
 * </ul>
 *
 * <p>The template match is linear (successive {@code indexOf} between the literal parts, found with the one
 * {@code {{…}}} scanner, {@link HttpVars#referencePattern()}): a regex with a {@code .*?} per reference backtracked
 * for minutes on a long URL. Pure — tested by {@code HttpRerunTargetTest}.
 */
final class HttpRerunTarget {

    private HttpRerunTarget() {}

    /** The index in {@code requests} of the request {@code entry} came from, or -1. */
    static int find(List<HttpFile.Request> requests, HttpHistoryEntry entry) {
        var ex = entry.exchange();
        return find(
                requests,
                entry.name(),
                ex.method(),
                ex.url(),
                entry.urlTemplate(),
                entry.fingerprint(),
                entry.requestIndex());
    }

    /** {@link #find(List, String, String, String, String, String, int)} for an entry known only by what it sent. */
    static int find(List<HttpFile.Request> requests, String name, String method, String sentUrl, int formerIndex) {
        return find(requests, name, method, sentUrl, null, null, formerIndex);
    }

    /**
     * The index in {@code requests} of the request {@code name} / {@code method} {@code sentUrl} came from, or -1.
     * {@code urlTemplate} and {@code fingerprint} (null when unknown) are the request's URL as written and the digest
     * of its text when it ran; {@code formerIndex} (-1 unknown) only picks between equally good matches.
     */
    static int find(
            List<HttpFile.Request> requests,
            String name,
            String method,
            String sentUrl,
            String urlTemplate,
            String fingerprint,
            int formerIndex) {
        if (requests == null || requests.isEmpty()) {
            return -1;
        }
        boolean named = name != null && !name.isBlank();
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            HttpFile.Request r = requests.get(i);
            if (named ? name.strip().equals(nameOf(r)) : sameMethod(method, r.method())) {
                pool.add(i);
            }
        }
        if (pool.isEmpty()) {
            return -1;
        }
        if (named && pool.size() == 1) {
            return pool.get(0);
        }
        List<Integer> unchanged = fingerprint == null
                ? List.of()
                : filter(pool, i -> fingerprint.equals(fingerprint(HttpFile.parseRequest(requests.get(i)))));
        if (!unchanged.isEmpty()) {
            return nearest(unchanged, formerIndex);
        }
        List<Integer> asWritten = urlTemplate == null
                ? List.of()
                : filter(
                        pool,
                        i -> sameMethod(method, requests.get(i).method())
                                && urlTemplate
                                        .strip()
                                        .equals(strip(requests.get(i).url())));
        if (!asWritten.isEmpty()) {
            return nearest(asWritten, formerIndex);
        }
        if (!named && urlTemplate != null) {
            return -1; // the run's own request is no longer there as written: never guess at another
        }
        List<Integer> sent = filter(
                pool,
                i -> sameMethod(method, requests.get(i).method())
                        && templateMatches(requests.get(i).url(), sentUrl));
        if (!sent.isEmpty()) {
            return nearest(sent, formerIndex);
        }
        return named ? nearest(pool, formerIndex) : -1;
    }

    /**
     * A digest of what a request sends as written: method, URL, headers, body and body file, before any {@code {{…}}}
     * is resolved. Two requests with the same URL and different bodies differ. Pure.
     */
    static String fingerprint(HttpFile.Parsed p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.method()).append('\n').append(strip(p.url())).append('\n');
        for (String[] h : p.headers()) {
            sb.append(h[0]).append(": ").append(h[1]).append('\n');
        }
        sb.append('\n').append(p.body() == null ? "" : p.body().strip());
        if (p.bodyRef() != null) {
            sb.append("\n<")
                    .append(p.bodyRef().substitute() ? "@" : "")
                    .append(p.bodyRef().path());
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JDK has SHA-256
        }
    }

    private static String nameOf(HttpFile.Request r) {
        return r.name() == null || r.name().isBlank() ? null : r.name().strip();
    }

    private static boolean sameMethod(String a, String b) {
        String x = a == null || a.isBlank() ? "GET" : a.strip();
        String y = b == null || b.isBlank() ? "GET" : b.strip();
        return x.equalsIgnoreCase(y);
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }

    private static List<Integer> filter(List<Integer> pool, IntPredicate keep) {
        List<Integer> out = new ArrayList<>();
        for (int i : pool) {
            if (keep.test(i)) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * Whether {@code template} (with {@code {{…}}} references) can expand to {@code url}, the URL a run sent. The URL
     * is also tried percent-decoded (the run encodes spaces and non-ASCII letters), a template without a scheme is
     * matched without the URL's ({@code example.com/x} sent as {@code http://example.com/x}), and an origin-form
     * template ({@code /path}, with a {@code Host} header) against the URL's path. Linear in the lengths.
     */
    static boolean templateMatches(String template, String url) {
        if (template == null || url == null) {
            return false;
        }
        String t = template.strip();
        for (String u : new String[] {url.strip(), percentDecoded(url.strip())}) {
            String target = t.startsWith("/") ? pathOf(u) : !hasScheme(t) && !t.startsWith("{{") ? withoutScheme(u) : u;
            if (globMatches(t, target)) {
                return true;
            }
        }
        return false;
    }

    /** {@code template}'s literal parts in order around its references, matched leftmost-first. */
    private static boolean globMatches(String template, String url) {
        List<String> literals = new ArrayList<>();
        Matcher m = HttpVars.referencePattern().matcher(template);
        int at = 0;
        while (m.find()) {
            literals.add(template.substring(at, m.start()));
            at = m.end();
        }
        literals.add(template.substring(at));
        int refs = literals.size() - 1;
        if (refs == 0) {
            return template.equals(url);
        }
        String first = literals.get(0);
        String last = literals.get(refs);
        if (!url.startsWith(first) || !url.endsWith(last) || url.length() - last.length() < first.length()) {
            return false;
        }
        int pos = first.length();
        int end = url.length() - last.length();
        for (int k = 1; k <= refs; k++) {
            String next = literals.get(k);
            int found = k == refs ? end : url.indexOf(next, pos);
            if (found < pos || found + (k == refs ? 0 : next.length()) > end) {
                return false;
            }
            if (segment(literals.get(k - 1), next) && url.substring(pos, found).indexOf('/') >= 0) {
                return false; // {{id}} between slashes stands for one path segment, not "users/1/delete"
            }
            pos = found + (k == refs ? 0 : next.length());
        }
        return true;
    }

    /** Whether the reference between {@code before} and {@code after} sits in a path segment of its own. */
    private static boolean segment(String before, String after) {
        return before.endsWith("/") && (after.isEmpty() || "/?#".indexOf(after.charAt(0)) >= 0);
    }

    private static boolean hasScheme(String s) {
        int colon = s.indexOf("://");
        return colon > 0 && s.substring(0, colon).chars().allMatch(Character::isLetter);
    }

    private static String withoutScheme(String url) {
        int colon = url.indexOf("://");
        return colon > 0 ? url.substring(colon + 3) : url;
    }

    private static String pathOf(String url) {
        int colon = url.indexOf("://");
        if (colon < 0) {
            return url;
        }
        int slash = url.indexOf('/', colon + 3);
        return slash < 0 ? "/" : url.substring(slash);
    }

    /** {@code s} with its {@code %XX} escapes decoded as UTF-8 (a malformed escape is kept as is). */
    private static String percentDecoded(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo);
                    i += 3;
                    continue;
                }
            }
            int cp = s.codePointAt(i);
            byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
            i += Character.charCount(cp);
        }
        return out.toString(StandardCharsets.UTF_8);
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
