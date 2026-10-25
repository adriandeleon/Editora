package com.editora.ui;

import java.util.List;
import java.util.regex.Pattern;

import com.editora.http.HttpClientService;
import com.editora.http.HttpResult;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What kind of failure a request that got no response met — connection refused, unknown host, a timeout, a
 * certificate… — named by the title of the response view's error card ({@link HttpErrorCard}). A result keeps only
 * its error text, so the kind is read back from it: each {@link com.editora.http.HttpErrors} message of the UI
 * language is matched as a pattern (its arguments as wildcards). An error saved in another language reads as {@link
 * #OTHER}. Pure apart from the message catalog — tested by {@code HttpFailureKindTest}.
 */
enum HttpFailureKind {
    REFUSED("httppanel.failure.refused", "status.http.error.refused"),
    UNKNOWN_HOST("httppanel.failure.unknownHost", "status.http.error.unknownHost"),
    NO_ROUTE("httppanel.failure.noRoute", "status.http.error.noRoute"),
    TIMEOUT(
            "httppanel.failure.timeout",
            "status.http.error.connectTimeout",
            "status.http.error.connectTimeoutTls",
            "status.http.error.timeout",
            "status.http.error.timeoutDirective"),
    CERTIFICATE("httppanel.failure.certificate", "status.http.error.untrustedCert", "status.http.error.certName"),
    TLS("httppanel.failure.tls", "status.http.error.tls"),
    CLOSED("httppanel.failure.closed", "status.http.error.closedHttp", "status.http.error.closed"),
    NOT_HTTP("httppanel.failure.notHttp", "status.http.error.notHttp"),
    BAD_URL(
            "httppanel.failure.badUrl",
            "status.http.error.badUrl",
            "status.http.error.scheme",
            "status.http.error.noScheme"),
    /** Not sent because a pre-request script did not run or stopped with an error. */
    SCRIPT("httppanel.failure.script", "status.http.preScriptNotRun"),
    /** Never sent: refused by the trust prompt, a body file that cannot be read, an untitled buffer's "save first". */
    NOT_SENT("httppanel.notSent"),
    CANCELLED("httppanel.failure.cancelled"),
    OTHER("httppanel.requestFailed");

    /** Stands in for each argument when a message is turned into a pattern. */
    private static final String ARG = "\u0001";

    private final String titleKey;
    private final List<String> messageKeys;

    HttpFailureKind(String titleKey, String... messageKeys) {
        this.titleKey = titleKey;
        this.messageKeys = List.of(messageKeys);
    }

    /** The error card's title: "Connection refused", "Host not found", "Timed out"… */
    String title() {
        return tr(titleKey);
    }

    /** Whether the failure is about TLS or a certificate. */
    boolean tls() {
        return this == CERTIFICATE || this == TLS;
    }

    /** The kind of {@code r}'s failure; {@link #OTHER} when it did not fail or is not recognised. */
    static HttpFailureKind of(HttpResult r) {
        if (r == null || !r.failed()) {
            return OTHER;
        }
        String error = r.error();
        if (HttpClientService.CANCELLED.equals(error)) {
            return CANCELLED;
        }
        if (HttpStatusText.preRequestFailed(r) || SCRIPT.matches(error)) {
            return SCRIPT;
        }
        if (HttpStatusText.notSent(r)) {
            return NOT_SENT;
        }
        for (HttpFailureKind k : values()) {
            if (k.matches(error)) {
                return k;
            }
        }
        return OTHER;
    }

    private boolean matches(@Nullable String error) {
        if (error == null) {
            return false;
        }
        String e = error.strip();
        for (String key : messageKeys) {
            if (pattern(key).matcher(e).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code key}'s message as a pattern: its text quoted, each argument any text, followed by anything (the engine
     * appends the URL in brackets).
     */
    static Pattern pattern(String key) {
        String shown = tr(key, ARG, ARG, ARG);
        String[] parts = shown.split(ARG, -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(".*?");
            }
            sb.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(sb.append(".*").toString(), Pattern.DOTALL);
    }
}
