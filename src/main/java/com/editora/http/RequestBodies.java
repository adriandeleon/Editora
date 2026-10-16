package com.editora.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.editora.i18n.Messages.tr;

/**
 * Builds a request's body for {@link HttpClientService}: what it publishes, what the history and the cURL export
 * show of it, what a Digest {@code auth-int} answer hashes, and the files it streams from. Moved out of the service
 * so the transport and the body rules are read separately; behaviour is the service's own.
 */
final class RequestBodies {

    /** Up to this size an uploaded {@code < file} body is also shown as text in the history and cURL views. */
    private static final long SHOWN_FILE_BODY_BYTES = 1024 * 1024;

    private RequestBodies() {}

    /**
     * The request body: its publisher, a display string (for the history/cURL view), and how it went out (a file
     * streamed as is, multipart fields) for the cURL export. A non-null {@code problem} means the body the request
     * refers to could not be read and the request must not be sent. {@code digestFeed} feeds the body to a
     * {@code qop=auth-int} Digest answer; a streamed body (a {@code < file}, a multipart form with file parts) is
     * read again for it as it goes, never held in memory whole.
     */
    record Content(
            HttpRequest.BodyPublisher publisher,
            String display,
            String problem,
            DigestAuth.BodyFeed digestFeed,
            Path file,
            List<HttpExchange.FormPart> parts,
            java.io.Closeable files) {

        Content(
                HttpRequest.BodyPublisher publisher,
                String display,
                String problem,
                DigestAuth.BodyFeed digestFeed,
                Path file,
                List<HttpExchange.FormPart> parts) {
            this(publisher, display, problem, digestFeed, file, parts, null);
        }

        /** Closes the files the body streams from: after the exchange, and at once when the run is cancelled. */
        void release() {
            if (files != null) {
                try {
                    files.close();
                } catch (IOException ignored) {
                    // closing to release the file: nothing more to do
                }
            }
        }

        static Content of(byte[] bytes, String display) {
            return new Content(
                    HttpRequest.BodyPublishers.ofByteArray(bytes),
                    display,
                    null,
                    md -> md.update(bytes),
                    null,
                    List.of());
        }

        static Content unreadable(String problem) {
            return new Content(
                    HttpRequest.BodyPublishers.noBody(), "(" + problem + ")", problem, md -> {}, null, List.of());
        }
    }

    /** Feeds {@code file} to a digest in chunks. */
    private static void feedFile(Path file, java.security.MessageDigest md) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(new java.security.DigestOutputStream(OutputStream.nullOutputStream(), md));
        }
    }

    /**
     * The body {@code request} sends: a multipart form (parts substituted, file parts streamed), a {@code < file}
     * streamed as is, a {@code <@ file} or inline body substituted and encoded with {@code contentType}'s charset.
     * May set the form's boundary in {@code headers}; adds encoding warnings to {@code warnings}.
     */
    static Content resolve(
            HttpFile.Parsed request,
            Path baseDir,
            Function<String, String> sub,
            String contentType,
            List<String[]> headers,
            List<String> warnings) {
        // multipart/form-data: assemble the parts (inline and <@ parts substituted, < ./file parts streamed).
        if (Multipart.isMultipart(contentType)) {
            String boundary = Multipart.boundaryOf(contentType);
            if (boundary.isEmpty()) {
                boundary = "EditoraBoundary" + Long.toHexString(System.nanoTime());
                setHeader(headers, "Content-Type", contentType + "; boundary=" + boundary);
            }
            List<String> problems = new ArrayList<>();
            List<Multipart.Part> parsed = Multipart.parse(request.body(), boundary);
            // File parts stay files: the body is streamed from them while it is sent, its length known up front.
            Multipart.Body form = Multipart.assemble(parsed, boundary, baseDir, sub, problems);
            if (!problems.isEmpty()) {
                return Content.unreadable(String.join("; ", problems));
            }
            HttpRequest.BodyPublisher publisher;
            try {
                publisher = form.publisher();
            } catch (java.io.FileNotFoundException gone) {
                return Content.unreadable(tr("http.multipart.notFound", gone.getMessage()));
            }
            return new Content(
                    publisher,
                    tr("http.body.multipart", String.valueOf(form.length())),
                    null,
                    form::feed,
                    null,
                    formParts(form.parts()),
                    form);
        }
        Charset charset = HttpResult.charsetOf(contentType);
        // external body: < ./file (raw) or <@ ./file (substituted), optional encoding.
        if (request.bodyRef() != null && baseDir != null) {
            try {
                HttpFile.BodyRef ref = request.bodyRef();
                Charset cs = ref.encoding() == null ? StandardCharsets.UTF_8 : Charset.forName(ref.encoding());
                Path bodyFile = HttpPaths.contained(baseDir, ref.path());
                if (bodyFile == null) {
                    return Content.unreadable(tr("http.body.outside", ref.path()));
                }
                if (!ref.substitute()) {
                    // "< file": the file's bytes are the body, untouched and streamed — a decode/re-encode round
                    // trip through String corrupts anything that is not valid UTF-8 (an image, a protobuf), and a
                    // multi-gigabyte upload must not be read into memory first.
                    long size = Files.size(bodyFile);
                    String shown = shownFileBody(bodyFile, size, ref.path(), cs);
                    FileBodyPublisher upload = new FileBodyPublisher(bodyFile);
                    return new Content(upload, shown, null, md -> feedFile(bodyFile, md), bodyFile, List.of(), upload);
                }
                String content = sub.apply(new String(Files.readAllBytes(bodyFile), cs));
                return Content.of(encode(content, charset, warnings), content);
            } catch (Exception e) {
                return Content.unreadable(
                        tr("http.body.notFound", request.bodyRef().path()));
            }
        }
        if (request.bodyRef() != null) {
            return Content.unreadable(
                    tr("http.body.needsFolder", request.bodyRef().path()));
        }
        String body = sub.apply(request.body());
        if (body == null || body.isBlank()) {
            return new Content(HttpRequest.BodyPublishers.noBody(), "", null, md -> {}, null, List.of());
        }
        return Content.of(encode(body, charset, warnings), body);
    }

    /** What the history shows for a {@code < file} body: its text when small and textual, else a note. */
    private static String shownFileBody(Path file, long size, String shownPath, Charset cs) throws IOException {
        if (size > SHOWN_FILE_BODY_BYTES) {
            return tr("http.body.fromFile", shownPath, HttpResponseFormat.humanSize(size));
        }
        byte[] raw = Files.readAllBytes(file);
        return HttpResult.looksBinary(raw, null)
                ? tr("http.body.binaryFromFile", shownPath, String.valueOf(raw.length))
                : new String(raw, cs);
    }

    /**
     * {@code text} in {@code charset} — the one the request's {@code Content-Type} declares, else UTF-8 — with a
     * warning when a character has no form in it (it goes out as {@code ?}).
     */
    static byte[] encode(String text, Charset charset, List<String> warnings) {
        try {
            ByteBuffer b = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        } catch (CharacterCodingException | UnsupportedOperationException e) {
            warnings.add(tr("status.http.warn.unmappable", charset.name()));
            return text.getBytes(charset);
        }
    }

    private static final Pattern DISPOSITION_PARAM =
            Pattern.compile("(?i)(?:^|;)\\s*(name|filename)\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^;]*)");

    /**
     * The multipart parts as form fields for the cURL export, from the parts as they went out: names, the text sent
     * (an inline value, or a {@code <@ ./file} part after substitution) and the files streamed.
     */
    private static List<HttpExchange.FormPart> formParts(List<Multipart.Resolved> parts) {
        List<HttpExchange.FormPart> out = new ArrayList<>();
        for (Multipart.Resolved p : parts) {
            String name = null;
            String filename = null;
            String type = null;
            for (Map.Entry<String, String> h : p.headers().entrySet()) {
                if (h.getKey().equalsIgnoreCase("Content-Disposition")) {
                    Matcher m = DISPOSITION_PARAM.matcher(h.getValue());
                    while (m.find()) {
                        String v = m.group(2).strip();
                        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                            v = v.substring(1, v.length() - 1);
                        }
                        if (m.group(1).equalsIgnoreCase("name")) {
                            name = v;
                        } else {
                            filename = v;
                        }
                    }
                } else if (h.getKey().equalsIgnoreCase("Content-Type")) {
                    type = h.getValue();
                }
            }
            out.add(
                    p.file() != null
                            ? new HttpExchange.FormPart(
                                    name, null, p.file().toAbsolutePath().toString(), filename, type)
                            : new HttpExchange.FormPart(name, p.text() == null ? "" : p.text(), null, filename, type));
        }
        return out;
    }

    private static void setHeader(List<String[]> headers, String name, String value) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) {
                h[1] = value;
                return;
            }
        }
        headers.add(new String[] {name, value});
    }
}
