package com.editora.ui;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.http.CapturedResponses;
import com.editora.http.DynamicVars;
import com.editora.http.HttpClientService;
import com.editora.http.HttpFile;
import com.editora.http.HttpVars;
import com.editora.http.Multipart;

/**
 * Two pure decisions about a request before it is sent. {@link #needsFolder} lists what an unsaved
 * ("scratch") {@code .http} buffer cannot do, because it has no folder to resolve paths against: a body file
 * ({@code < ./body.json}, {@code <@ …}), a multipart file part, a {@code >>} response file, a {@code $dotenv}
 * lookup. {@link #label} is the request line as it will be sent, for the "Running …" status, with the
 * variables filled in. Tested by {@code HttpScratchTest}.
 */
final class HttpScratch {

    /** The same {@code {{…}}} scanner a run uses. */
    private static final Pattern VAR = HttpVars.referencePattern();

    private HttpScratch() {}

    /**
     * What {@code request} reads or writes relative to its file's folder, one short description each (empty
     * when it can run from an unsaved buffer). The {@code $dotenv} reads come from the request's dry resolution
     * against {@code envVars} (see {@link HttpClientService#preview}), so one behind an {@code @var} line or an
     * environment value is found exactly when the run would read it.
     */
    static List<String> needsFolder(HttpFile.Parsed request, Map<String, String> envVars) {
        List<String> out = new ArrayList<>();
        if (request.bodyRef() != null) {
            out.add((request.bodyRef().substitute() ? "<@ " : "< ")
                    + request.bodyRef().path());
        }
        String contentType = header(request.headers(), "Content-Type");
        if (Multipart.isMultipart(contentType)) {
            String boundary = Multipart.boundaryOf(contentType);
            if (!boundary.isEmpty()) {
                for (Multipart.Part part : Multipart.parse(request.body(), boundary)) {
                    if (part.filePath() != null && !part.filePath().isBlank()) {
                        out.add("< " + part.filePath());
                    }
                }
            }
        }
        for (HttpFile.Redirect r : request.redirects()) {
            out.add((r.force() ? ">>! " : ">> ") + r.path());
        }
        List<String> reads;
        try {
            reads = HttpClientService.preview(request, envVars, null, null).reads();
        } catch (RuntimeException unresolvable) {
            reads = List.of(); // the run reports it
        }
        for (String read : reads) {
            if (read.startsWith("$dotenv")) {
                out.add(read);
            }
        }
        return out;
    }

    /**
     * The request line as it will be sent — {@code GET http://localhost:8080/users/7} for {@code GET
     * {{host}}/users/{{id}}} — resolved with the scope the run uses. Dynamic {@code {{$…}}} values (a fresh
     * {@code $uuid}, {@code $processEnv} secrets) and anything that does not resolve are left as written: the
     * label must neither differ from what is sent nor show more than the response view will.
     */
    static String label(
            HttpFile.Parsed request,
            Map<String, String> envVars,
            CapturedResponses captured,
            Path dir,
            ZonedDateTime now) {
        String url = request.url() == null ? "" : request.url();
        if (url.indexOf("{{") < 0) {
            return request.method() + " " + url;
        }
        HttpVars.Scope scope;
        try {
            scope = HttpVars.scope(envVars, request.variables(), now, captured, dir, true); // secrets stay as written
        } catch (RuntimeException e) {
            return request.method() + " " + url;
        }
        Matcher m = VAR.matcher(url);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).strip();
            String replacement = m.group();
            if (!DynamicVars.isDynamic(name) && !dynamicDefinition(name, request.variables(), envVars)) {
                HttpVars.Unresolved missing = HttpVars.Unresolved.dry();
                String v = HttpVars.substitute(m.group(), scope, now, captured, dir, missing);
                if (missing.isEmpty() && missing.secretReads().isEmpty()) {
                    replacement = v;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return request.method() + " " + sb;
    }

    /** Whether {@code name} is defined (last {@code @var}, else the environment) by a dynamic value. */
    private static boolean dynamicDefinition(String name, List<String[]> fileVars, Map<String, String> envVars) {
        String raw = null;
        if (fileVars != null) {
            for (String[] pair : fileVars) {
                if (pair.length > 1 && pair[0].equals(name)) {
                    raw = pair[1];
                }
            }
        }
        if (raw == null && envVars != null) {
            raw = envVars.get(name);
        }
        return raw != null && raw.replace(" ", "").contains("{{$");
    }

    private static String header(List<String[]> headers, String name) {
        if (headers != null) {
            for (String[] h : headers) {
                if (h.length > 1 && h[0].equalsIgnoreCase(name)) {
                    return h[1];
                }
            }
        }
        return null;
    }
}
