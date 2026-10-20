package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.config.HttpResponseHistory;
import com.editora.http.SecretMask;
import org.jspecify.annotations.Nullable;

/**
 * Masks the values a run read from {@code $processEnv}/{@code $env}/{@code $dotenv} in what the HTTP Client keeps:
 * the saved history and the History label (adl, 2026-10-15). Each value is replaced by {@link
 * HttpResponseHistory#MASK} wherever it appears — as written, and in the percent-encoded forms a URL carries it in.
 * A value shorter than {@link #MIN_LENGTH} is left alone: masking every "1" or "on" of a response would wreck it and
 * hide nothing. Pure — tested by {@code HttpSecretMaskTest}.
 */
final class HttpSecretMask {

    /** The shortest value that is masked. */
    static final int MIN_LENGTH = SecretMask.MIN_LENGTH;

    private HttpSecretMask() {}

    /** {@code text} with every form of each of {@code secrets} masked; {@code text} itself when there is nothing to do. */
    static @Nullable String mask(@Nullable String text, List<String> secrets) {
        return SecretMask.mask(text, secrets);
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
}
