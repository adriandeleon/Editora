package com.editora.http;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Locale;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import static com.editora.i18n.Messages.tr;

/**
 * Plain-language messages for a request that got no response. The JDK reports most failures as a bare
 * {@code ConnectException} (refused port and unknown host alike) or as certificate-path jargon, which the
 * response view used to show as is. The cause chain is walked and each common failure gets a sentence that
 * says what to check. The URL is appended by the caller. Pure apart from the message catalog, so it is
 * unit-tested.
 */
public final class HttpErrors {

    private HttpErrors() {}

    /**
     * The message for {@code failure}, a request to {@code url} with header timeout {@code timeout} and
     * connect timeout {@code connectTimeout}; {@code explicitTimeout} tells whether the request set
     * {@code @timeout}.
     */
    public static String message(
            Throwable failure, String url, Duration timeout, Duration connectTimeout, boolean explicitTimeout) {
        String host = hostPort(url);
        if (failure instanceof OutOfMemoryError) {
            return tr("status.http.error.memory");
        }
        if (failure instanceof ContentDecoding.DecodeException d) {
            return tr("status.http.error.decode", d.encoding(), detail(d));
        }
        if (find(failure, HttpConnectTimeoutException.class) != null) {
            boolean https = url != null && url.regionMatches(true, 0, "https:", 0, 6);
            return tr(
                    https ? "status.http.error.connectTimeoutTls" : "status.http.error.connectTimeout",
                    host,
                    seconds(connectTimeout));
        }
        if (find(failure, HttpTimeoutException.class) != null) {
            return tr(
                    explicitTimeout ? "status.http.error.timeoutDirective" : "status.http.error.timeout",
                    seconds(timeout));
        }
        if (find(failure, UnresolvedAddressException.class) != null
                || find(failure, UnknownHostException.class) != null) {
            return tr("status.http.error.unknownHost", hostOnly(url));
        }
        if (find(failure, NoRouteToHostException.class) != null) {
            return tr("status.http.error.noRoute", host);
        }
        SSLException ssl = find(failure, SSLException.class);
        if (ssl != null) {
            String all = messages(failure).toLowerCase(Locale.ROOT);
            if (find(failure, CertPathValidatorException.class) != null
                    || all.contains("pkix path")
                    || all.contains("unable to find valid certification path")) {
                return tr("status.http.error.untrustedCert", hostOnly(url));
            }
            if (all.contains("no subject alternative") || all.contains("no name matching")) {
                return tr("status.http.error.certName", hostOnly(url));
            }
            if (find(failure, CertificateException.class) != null || ssl instanceof SSLHandshakeException) {
                return tr("status.http.error.tls", hostOnly(url), detail(ssl));
            }
            return tr("status.http.error.tls", hostOnly(url), detail(ssl));
        }
        if (find(failure, ConnectException.class) != null) {
            return tr("status.http.error.refused", host);
        }
        String all = messages(failure).toLowerCase(Locale.ROOT);
        if (all.contains("too many bytes in request body") || all.contains("too few bytes returned by the publisher")) {
            // The JDK checks the body against the length announced up front: a file part changed while it was sent.
            return tr("status.http.error.bodyChanged");
        }
        if (all.contains("invalid status line") || all.contains("bad status line")) {
            return tr("status.http.error.notHttp", host);
        }
        if (all.contains("port out of range")) {
            return tr("status.http.error.badUrl", detail(failure));
        }
        if (all.contains("invalid uri scheme")) {
            return tr("status.http.error.scheme", scheme(url));
        }
        IllegalArgumentException bad = find(failure, IllegalArgumentException.class);
        if (bad != null && (all.contains("uri") || all.contains("illegal character") || all.contains("scheme"))) {
            return tr("status.http.error.badUrl", detail(bad));
        }
        if (find(failure, java.io.EOFException.class) != null
                || all.contains("header parser received no bytes")
                || all.contains("connection reset")
                || all.contains("connection closed")) {
            boolean plain = url != null && url.regionMatches(true, 0, "http:", 0, 5);
            // An https-only port answers a plain request by hanging up: say what to try.
            return tr(plain ? "status.http.error.closedHttp" : "status.http.error.closed", host);
        }
        return tr("status.http.error.other", detail(failure));
    }

    /** The first throwable of {@code type} in the cause chain, or null. */
    private static <T extends Throwable> T find(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }

    private static String messages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append(' ');
            }
        }
        return sb.toString();
    }

    /** The throwable's own message, or its simple class name when it has none. */
    private static String detail(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m.strip();
    }

    private static String seconds(Duration d) {
        if (d == null) {
            return "?";
        }
        long ms = d.toMillis();
        return ms % 1000 == 0 ? (ms / 1000) + " s" : ms + " ms";
    }

    private static URI uri(String url) {
        try {
            return url == null ? null : URI.create(url);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String hostOnly(String url) {
        URI u = uri(url);
        return u == null || u.getHost() == null ? String.valueOf(url) : u.getHost();
    }

    private static String hostPort(String url) {
        URI u = uri(url);
        if (u == null || u.getHost() == null) {
            return String.valueOf(url);
        }
        int port = u.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
        }
        return u.getHost() + ":" + port;
    }

    private static String scheme(String url) {
        URI u = uri(url);
        return u == null || u.getScheme() == null ? "" : u.getScheme();
    }
}
