package com.editora.http;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static com.editora.i18n.Messages.tr;

/**
 * The secrets policy of a run: what its requests would read and where they would send it, from a dry resolution by
 * the same code as a send ({@link #preview}); one question to the run's {@link SecretGate} about all of it
 * ({@link #ask}); and, for each send, why it may not go out ({@link #refusalFor}) — a read the gate did not approve,
 * or a host the prompt did not name. A request that is not sent and read a secret is reported from its dry
 * resolution ({@link #unsentReport}), so no refused value is shown or saved.
 *
 * <p>Pure apart from the body files a preview reads, so it runs on the run's worker.
 */
public final class SecretApproval {

    private SecretApproval() {}

    /**
     * What a run may read and where it may send it: the approved {@code reads} and the {@code hosts} the prompt named
     * ({@code anyHost} when it said a host is only known at the send — chosen by an earlier response, or made of a
     * secret or a {@code $…} value), or nothing with the gate's {@code refusal}.
     */
    record Grant(Set<String> reads, Set<String> hosts, boolean anyHost, String refusal) {

        static Grant refused(String refusal) {
            return new Grant(Set.of(), Set.of(), false, refusal);
        }
    }

    /**
     * What a request would read and where it would go, from a dry resolution that runs the same code as a send
     * ({@link PreparedRequest#dry}) but keeps every secret source as written: the {@code reads} (see
     * {@link HttpVars.Unresolved#secretReads()}) and the {@code hosts} ({@code host:port}) it would contact.
     * {@code hostFromResponse}: the host comes (directly or through an {@code @var} line) from an earlier response, so
     * it is only known once that response is in; {@code hostUnfixed}: it may differ at the send (it also uses a secret
     * or a {@code $…} built-in). {@code sendable}: false for a request that is never sent (GraphQL/WebSocket/gRPC, an
     * unreadable body file), which therefore asks nothing.
     */
    public record Preview(
            List<String> reads, List<String> hosts, boolean hostFromResponse, boolean hostUnfixed, boolean sendable) {

        public Preview(List<String> reads, List<String> hosts) {
            this(reads, hosts, false, false, true);
        }
    }

    private static final Grant NOTHING_READ = new Grant(Set.of(), Set.of(), false, null);

    /**
     * The dry resolution of {@code request}: URL, headers, inline and {@code <@} bodies, multipart parts (their
     * {@code <@} files included), {@code >>} targets, the {@code @var} lines above it and the environment values they
     * use — every secret source read on the way, and the host it would be sent to. No secret value is read into the
     * result. Reads the request's body files, so call it off the FX thread.
     */
    public static Preview preview(
            HttpFile.Parsed request, Map<String, String> vars, Path baseDir, CapturedResponses captured) {
        PreparedRequest.Dry dry = PreparedRequest.dry(request, vars, baseDir, captured);
        PreparedRequest p = dry.prepared();
        // What the host is made of: the request line up to its path, and a Host header (origin form).
        HttpVars.Unresolved hostSink = HttpVars.Unresolved.dry();
        HttpVars.substitute(hostPart(request.url()), dry.scope(), dry.now(), captured, baseDir, hostSink);
        for (String[] h : request.headers()) {
            if (h[0] != null && h[0].strip().equalsIgnoreCase("Host")) {
                HttpVars.substitute(h[1], dry.scope(), dry.now(), captured, baseDir, hostSink);
            }
        }
        boolean fromResponse = !hostSink.responseReads().isEmpty();
        boolean unfixed = fromResponse
                || !hostSink.secretReads().isEmpty()
                || !hostSink.dynamicReads().isEmpty();
        boolean sendable = request.error() == null && p.body().problem() == null;
        return new Preview(
                dry.sink().secretReads(), List.of(HttpHosts.authority(p.targetUrl())), fromResponse, unfixed, sendable);
    }

    /**
     * Asks {@code gate} once about every secret the {@code requests} would read: null for an unchecked run; nothing is
     * asked when none do, for a request that is never sent, or once {@code cancelled} (a run cancelled or replaced
     * before its prompt).
     */
    static Grant ask(
            SecretGate gate,
            BooleanSupplier cancelled,
            List<HttpFile.Parsed> requests,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses captured) {
        if (gate == null || gate == SecretGate.UNCHECKED) {
            return null;
        }
        Set<String> reads = new LinkedHashSet<>();
        Set<String> hosts = new LinkedHashSet<>();
        boolean fromResponse = false;
        boolean anyHost = false;
        for (HttpFile.Parsed request : requests) {
            try {
                Preview p = preview(request, vars, baseDir, captured);
                if (p.sendable() && !p.reads().isEmpty()) {
                    reads.addAll(p.reads());
                    if (!p.hostFromResponse()) {
                        hosts.addAll(p.hosts());
                    }
                    fromResponse |= p.hostFromResponse();
                    anyHost |= p.hostUnfixed();
                }
            } catch (Throwable unreadable) {
                // nothing is approved for it: if its send does read a secret, the send is refused
            }
        }
        if (reads.isEmpty()) {
            return NOTHING_READ;
        }
        if (cancelled.getAsBoolean()) {
            return Grant.refused(HttpClientService.CANCELLED);
        }
        List<String> named = new ArrayList<>(hosts);
        if (fromResponse) {
            named.add(tr("dialog.httpTrust.dynamicHost")); // never a stale host from an earlier run's response
        }
        SecretGate.Verdict verdict;
        try {
            verdict = gate.check(List.copyOf(reads), List.copyOf(named), cancelled);
        } catch (RuntimeException failure) {
            verdict = SecretGate.Verdict.REFUSED;
        }
        String names = String.join(", ", reads);
        return switch (verdict == null ? SecretGate.Verdict.REFUSED : verdict) {
            case ALLOWED -> new Grant(reads, hosts, anyHost, null);
            case CANCELLED -> Grant.refused(HttpClientService.CANCELLED);
            case NO_FOLDER -> Grant.refused(tr("status.http.secretsNeedFolder", names));
            case REFUSED -> Grant.refused(tr("status.http.secretsRefused", names));
        };
    }

    /**
     * Why a send that read {@code unresolved}'s secrets may not go out under {@code approval}, or null: a read the
     * gate did not approve, or a host the prompt did not name. A null {@code approval} (an unchecked run) allows all.
     */
    static String refusalFor(Grant approval, HttpVars.Unresolved unresolved, String targetUrl) {
        if (approval == null || unresolved.secretReads().isEmpty()) {
            return null;
        }
        if (approval.refusal() != null) {
            return approval.refusal();
        }
        if (!approval.reads().containsAll(unresolved.secretReads())) {
            List<String> unapproved = new ArrayList<>(unresolved.secretReads());
            unapproved.removeAll(approval.reads());
            return tr("status.http.secretsUnapproved", String.join(", ", unapproved));
        }
        String host = HttpHosts.authority(targetUrl);
        if (!approval.anyHost() && !approval.hosts().contains(host)) {
            return tr("status.http.secretsHostUnapproved", String.join(", ", unresolved.secretReads()), host);
        }
        return null;
    }

    /**
     * The report of a request that is not sent although it read a secret: what it would have sent, from the dry
     * resolution — every secret source as written — so neither the response view nor the saved history holds a value
     * that was refused. {@code why} is the failure; {@code extraWarnings} follow the request's own.
     */
    static HttpExchange unsentReport(
            HttpFile.Parsed request,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses captured,
            String why,
            List<String> extraWarnings) {
        PreparedRequest.Dry dry = PreparedRequest.dry(request, vars, baseDir, captured);
        PreparedRequest p = dry.prepared();
        List<String> warnings = new ArrayList<>(p.warnings());
        warnings.addAll(dry.sink().warnings());
        warnings.addAll(extraWarnings);
        String url = p.targetUrl(); // not percent-encoded: the {{references}} read as written
        String label =
                p.method() + " " + (request.directives().noLog() ? PreparedRequest.withoutQueryValues(url) : url);
        return new HttpExchange(
                label,
                p.method(),
                url,
                p.headers(),
                p.body().display(),
                HttpResult.failure(why, warnings),
                p.details());
    }

    /**
     * The part of a request-line template that can decide its host: up to the first {@code /}, {@code ?} or
     * {@code #} after the scheme, a {@code {{reference}}} counted whole (it may hold a scheme, a host or a userinfo).
     * {@code GET {{base}}/x} gives {@code {{base}}}; {@code GET https://h.test/{{id}}} gives {@code https://h.test}.
     */
    static String hostPart(String template) {
        String u = template == null ? "" : template.strip();
        int scheme = u.indexOf("://");
        int i = scheme < 0 ? 0 : scheme + 3;
        while (i < u.length()) {
            if (u.startsWith("{{", i)) {
                int close = u.indexOf("}}", i + 2);
                if (close < 0) {
                    break;
                }
                i = close + 2;
                continue;
            }
            if ("/?#".indexOf(u.charAt(i)) >= 0) {
                break;
            }
            i++;
        }
        return u.substring(0, i);
    }
}
