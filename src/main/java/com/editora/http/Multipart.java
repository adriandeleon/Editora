package com.editora.http;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Pure {@code multipart/form-data} body builder for a request authored in a {@code .http} file. The body is
 * split on {@code --boundary} lines into {@link Part}s, each with its own headers and either inline content
 * or a {@code < ./file} reference; {@link #build} assembles the wire bytes (CRLF framing), substituting
 * {@code {{vars}}} in inline parts, part headers and file paths (and in {@code <@ ./file} parts). The request
 * itself is sent from {@link #assemble}'s {@link Body}, which keeps each {@code < ./file} part as the file and
 * streams it while sending, so a large upload is never read into memory. The split is unit-tested; the body is
 * verified on its byte output and its length.
 */
public final class Multipart {

    /** One multipart section: its {@code headers}, the {@code inlineBody} (when authored inline), or a
     *  {@code filePath} (relative to the request file) sent instead — streamed as is, or read and its {@code {{vars}}}
     *  substituted when {@code substituteFile} (a {@code <@ ./file} part). */
    public record Part(Map<String, String> headers, String inlineBody, String filePath, boolean substituteFile) {

        public Part(Map<String, String> headers, String inlineBody, String filePath) {
            this(headers, inlineBody, filePath, false);
        }
    }

    private Multipart() {}

    /** True for a multipart content type (so the executor builds the body via {@link #build}). */
    public static boolean isMultipart(String contentType) {
        return contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).contains("multipart/");
    }

    /** Extracts the {@code boundary=…} token from a multipart Content-Type, or {@code ""} if absent. */
    public static String boundaryOf(String contentType) {
        if (contentType == null) {
            return "";
        }
        int b = contentType.toLowerCase(java.util.Locale.ROOT).indexOf("boundary=");
        if (b < 0) {
            return "";
        }
        String v = contentType.substring(b + "boundary=".length()).trim();
        if (v.startsWith("\"")) {
            int close = v.indexOf('"', 1);
            return close > 0 ? v.substring(1, close) : v.substring(1); // a quoted boundary may hold a ';'
        }
        int semi = v.indexOf(';');
        return semi >= 0 ? v.substring(0, semi).trim() : v;
    }

    /** Splits a multipart {@code body} on {@code --boundary} lines into its parts. */
    public static List<Part> parse(String body, String boundary) {
        List<Part> parts = new ArrayList<>();
        if (body == null || boundary == null || boundary.isBlank()) {
            return parts;
        }
        String delim = "--" + boundary;
        List<String> current = null;
        for (String line : body.split("\n", -1)) {
            String t = line.strip();
            if (t.equals(delim) || t.equals(delim + "--")) {
                if (current != null) {
                    parts.add(toPart(current));
                }
                current = t.endsWith("--") ? null : new ArrayList<>();
            } else if (current != null) {
                current.add(line);
            }
        }
        if (current != null) {
            parts.add(toPart(current)); // an unterminated final part
        }
        return parts;
    }

    private static Part toPart(List<String> lines) {
        Map<String, String> headers = new LinkedHashMap<>();
        int i = 0;
        while (i < lines.size() && lines.get(i).strip().isEmpty()) {
            i++;
        }
        for (; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.strip().isEmpty()) {
                i++;
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(
                        line.substring(0, colon).strip(),
                        line.substring(colon + 1).strip());
            }
        }
        // The blank lines between a part's content and the next boundary are layout, not part of the value.
        int end = lines.size();
        while (end > i && lines.get(end - 1).strip().isEmpty()) {
            end--;
        }
        StringBuilder content = new StringBuilder();
        HttpFile.BodyRef file = null;
        for (; i < end; i++) {
            String t = lines.get(i).stripLeading();
            // Same rule as a request body: "< path" needs the whitespace, so an XML/HTML part stays inline.
            if (file == null && content.length() == 0 && HttpFile.isBodyFileRef(t)) {
                file = HttpFile.parseBodyRef(t);
                continue;
            }
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(lines.get(i));
        }
        return file == null
                ? new Part(headers, content.toString(), null, false)
                : new Part(headers, "", file.path(), file.substitute());
    }

    /**
     * One assembled part as it goes out: its headers and either the {@code text} sent (an inline value, or a
     * {@code <@ ./file} part after substitution) or the {@code file} streamed as is; both null when the part's
     * file could not be read (reported in the problems).
     */
    public record Resolved(Map<String, String> headers, String text, Path file) {}

    /**
     * An assembled {@code multipart/form-data} body: the framing and text parts as bytes, each {@code < ./file}
     * part as the file itself, read only while the body is sent. A large upload is therefore never held in
     * memory, and {@link #length()} — the sum of the pieces — keeps the request's {@code Content-Length} known.
     */
    public static final class Body implements java.io.Closeable {
        private final List<Object> pieces; // byte[] or Path, in wire order
        private final List<FileBodyPublisher> filePublishers = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final long length;
        private final List<Resolved> parts;

        private Body(List<Object> pieces, long length, List<Resolved> parts) {
            this.pieces = List.copyOf(pieces);
            this.length = length;
            this.parts = List.copyOf(parts);
        }

        /** The body's size in bytes, as sent (the files' sizes when the body was assembled). */
        public long length() {
            return length;
        }

        /** The parts, in order, as they go out. */
        public List<Resolved> parts() {
            return parts;
        }

        /** The files streamed into the body (the {@code < ./file} parts), in order. */
        public List<Path> streamedFiles() {
            List<Path> out = new ArrayList<>();
            for (Object p : pieces) {
                if (p instanceof Path file) {
                    out.add(file);
                }
            }
            return out;
        }

        /**
         * A publisher that sends the pieces in order, files straight from disk, with the summed length. Its files
         * are closed by {@link #close}, also while an upload is still reading them (a cancelled run).
         */
        public HttpRequest.BodyPublisher publisher() throws FileNotFoundException {
            List<HttpRequest.BodyPublisher> publishers = new ArrayList<>();
            for (Object p : pieces) {
                if (p instanceof Path file) {
                    FileBodyPublisher f = new FileBodyPublisher(file);
                    filePublishers.add(f);
                    publishers.add(f);
                } else {
                    publishers.add(HttpRequest.BodyPublishers.ofByteArray((byte[]) p));
                }
            }
            return HttpRequest.BodyPublishers.concat(publishers.toArray(HttpRequest.BodyPublisher[]::new));
        }

        /** Closes the files of every publisher made by {@link #publisher}. Idempotent. */
        @Override
        public void close() {
            filePublishers.forEach(FileBodyPublisher::close);
        }

        /** Writes the whole body to {@code out}, reading the files as it goes. */
        public void writeTo(OutputStream out) throws IOException {
            for (Object p : pieces) {
                if (p instanceof Path file) {
                    try (InputStream in = Files.newInputStream(file)) {
                        in.transferTo(out);
                    }
                } else {
                    out.write((byte[]) p);
                }
            }
        }

        /** Feeds the whole body to {@code digest} (a Digest {@code qop=auth-int} answer) without holding it. */
        public void feed(MessageDigest digest) throws IOException {
            writeTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
        }

        /** The whole body in memory: for small forms and tests. */
        public byte[] toBytes() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(length, 1 << 20));
            writeTo(out);
            return out.toByteArray();
        }
    }

    /**
     * Assembles the multipart body: CRLF framing, part headers and inline parts through {@code subst}, {@code <@}
     * file parts read and substituted, {@code < ./file} parts referenced to be streamed. Each file part that cannot
     * be read (missing, unreadable, or outside the request file's folder) is reported in {@code problems} and left
     * empty, so the caller can refuse to send a form whose upload would silently be empty.
     */
    public static Body assemble(
            List<Part> parts, String boundary, Path baseDir, Function<String, String> subst, List<String> problems) {
        Function<String, String> sub = subst == null ? Function.identity() : subst;
        List<Object> pieces = new ArrayList<>();
        List<Resolved> resolved = new ArrayList<>();
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        long length = 0;
        String delim = "--" + boundary;
        for (Part p : parts) {
            write(text, delim + "\r\n");
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, String> h : p.headers().entrySet()) {
                // Part headers take {{vars}} too: a filename="{{name}}" must not go out literally.
                String value = sub.apply(h.getValue());
                headers.put(h.getKey(), value);
                write(text, h.getKey() + ": " + value + "\r\n");
            }
            write(text, "\r\n");
            if (p.filePath() != null) {
                String path = sub.apply(p.filePath());
                Path file = null;
                try {
                    // contained: a file part must not read outside the request file's own folder
                    file = HttpPaths.contained(baseDir, path);
                } catch (RuntimeException unusable) {
                    file = null;
                }
                if (file == null) {
                    problems.add("multipart file is outside the request folder: " + path);
                    resolved.add(new Resolved(headers, null, null));
                } else if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                    problems.add("multipart file not found: " + path); // the part stays empty
                    resolved.add(new Resolved(headers, null, null));
                } else if (p.substituteFile()) {
                    try {
                        String content = sub.apply(Files.readString(file, StandardCharsets.UTF_8));
                        write(text, content);
                        resolved.add(new Resolved(headers, content, null));
                    } catch (IOException | RuntimeException unreadable) {
                        problems.add("multipart file not found: " + path);
                        resolved.add(new Resolved(headers, null, null));
                    }
                } else {
                    long size;
                    try {
                        size = Files.size(file);
                    } catch (IOException unreadable) {
                        problems.add("multipart file not found: " + path);
                        resolved.add(new Resolved(headers, null, null));
                        write(text, "\r\n");
                        continue;
                    }
                    length += flush(text, pieces);
                    pieces.add(file); // streamed while sending, never read into memory here
                    length += size;
                    resolved.add(new Resolved(headers, null, file));
                }
            } else {
                String value = sub.apply(p.inlineBody());
                write(text, value);
                resolved.add(new Resolved(headers, value, null));
            }
            write(text, "\r\n");
        }
        write(text, delim + "--\r\n");
        length += flush(text, pieces);
        return new Body(pieces, length, resolved);
    }

    /** Moves the pending text bytes into {@code pieces}; returns how many there were. */
    private static long flush(ByteArrayOutputStream text, List<Object> pieces) {
        if (text.size() == 0) {
            return 0;
        }
        byte[] bytes = text.toByteArray();
        text.reset();
        pieces.add(bytes);
        return bytes.length;
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
