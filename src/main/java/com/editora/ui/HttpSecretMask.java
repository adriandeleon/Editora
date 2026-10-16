package com.editora.ui;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.editora.config.HttpResponseHistory;
import com.editora.http.UrlEncoding;

/**
 * Masks the values a run read from {@code $processEnv}/{@code $env}/{@code $dotenv} in what the HTTP Client keeps:
 * the saved history and the History label (adl, 2026-10-15). Each value is replaced by {@link
 * HttpResponseHistory#MASK} wherever it appears — as written, and in the percent-encoded forms a URL carries it in.
 * A value shorter than {@link #MIN_LENGTH} is left alone: masking every "1" or "on" of a response would wreck it and
 * hide nothing. Pure — tested by {@code HttpSecretMaskTest}.
 */
final class HttpSecretMask {

    /** The shortest value that is masked. */
    static final int MIN_LENGTH = 4;

    private HttpSecretMask() {}

    /** {@code text} with every form of each of {@code secrets} masked; {@code text} itself when there is nothing to do. */
    static String mask(String text, List<String> secrets) {
        if (text == null || text.isEmpty() || secrets == null || secrets.isEmpty()) {
            return text;
        }
        String out = text;
        for (String form : forms(secrets)) {
            if (out.contains(form)) {
                out = out.replace(form, HttpResponseHistory.MASK);
            }
        }
        return out;
    }

    /** {@code headers} with {@link #mask} applied to each value. */
    static List<HttpResponseHistory.Header> maskValues(List<HttpResponseHistory.Header> headers, List<String> secrets) {
        if (headers == null || secrets == null || secrets.isEmpty()) {
            return headers;
        }
        List<HttpResponseHistory.Header> out = new ArrayList<>(headers.size());
        for (HttpResponseHistory.Header h : headers) {
            out.add(new HttpResponseHistory.Header(h.name(), mask(h.value(), secrets)));
        }
        return out;
    }

    /** Every form a secret may take in a saved text, longest first (so a longer form is not masked in part). */
    private static List<String> forms(List<String> secrets) {
        Set<String> forms = new LinkedHashSet<>();
        for (String s : secrets) {
            if (s == null || s.length() < MIN_LENGTH) {
                continue;
            }
            forms.add(s);
            forms.add(UrlEncoding.encodeIllegal(s)); // what the request's auto-encoding made of it in the URL
            String form = URLEncoder.encode(s, StandardCharsets.UTF_8);
            forms.add(form);
            forms.add(form.replace("+", "%20"));
        }
        List<String> sorted = new ArrayList<>(forms);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }
}
