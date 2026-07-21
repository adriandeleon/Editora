package com.editora.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Pure {@code multipart/form-data} body builder for a request authored in a {@code .http} file. The body is
 * split on {@code --boundary} lines into {@link Part}s, each with its own headers and either inline content
 * or a {@code < ./file} reference; {@link #build} assembles the wire bytes (CRLF framing), substituting
 * {@code {{vars}}} in inline parts and slurping file parts from the request file's directory. The split is
 * unit-tested; {@link #build} is verified on its byte output.
 */
public final class Multipart {

    /** One multipart section: its {@code headers}, the {@code inlineBody} (when authored inline), or a
     *  {@code filePath} (relative to the request file) to slurp instead. */
    public record Part(Map<String, String> headers, String inlineBody, String filePath) {}

    private Multipart() {}

    /** True for a multipart content type (so the executor builds the body via {@link #build}). */
    public static boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("multipart/");
    }

    /** Extracts the {@code boundary=…} token from a multipart Content-Type, or {@code ""} if absent. */
    public static String boundaryOf(String contentType) {
        if (contentType == null) {
            return "";
        }
        int b = contentType.toLowerCase().indexOf("boundary=");
        if (b < 0) {
            return "";
        }
        String v = contentType.substring(b + "boundary=".length()).trim();
        int semi = v.indexOf(';');
        if (semi >= 0) {
            v = v.substring(0, semi).trim();
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
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
        StringBuilder content = new StringBuilder();
        String filePath = null;
        for (; i < lines.size(); i++) {
            String t = lines.get(i).stripLeading();
            if (filePath == null && content.length() == 0 && t.startsWith("<") && !t.startsWith("<>")) {
                String ref = t.substring(1).strip();
                if (ref.startsWith("@")) {
                    ref = ref.substring(1).strip();
                }
                filePath = ref;
                continue;
            }
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(lines.get(i));
        }
        return new Part(headers, filePath == null ? content.toString() : "", filePath);
    }

    /** Assembles the multipart wire bytes; inline parts pass through {@code subst} and file parts are read
     *  from {@code baseDir}. Best-effort — a missing file leaves its part empty rather than throwing. */
    public static byte[] build(List<Part> parts, String boundary, Path baseDir, Function<String, String> subst) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String delim = "--" + boundary;
        for (Part p : parts) {
            write(out, delim + "\r\n");
            for (Map.Entry<String, String> h : p.headers().entrySet()) {
                write(out, h.getKey() + ": " + h.getValue() + "\r\n");
            }
            write(out, "\r\n");
            if (p.filePath() != null) {
                try {
                    // contained: a file part must not read outside the request file's own folder
                    Path file = HttpPaths.contained(baseDir, p.filePath());
                    out.writeBytes(file == null ? new byte[0] : Files.readAllBytes(file));
                } catch (Exception ignore) {
                    // a missing file part — leave it empty
                }
            } else {
                write(out, subst == null ? p.inlineBody() : subst.apply(p.inlineBody()));
            }
            write(out, "\r\n");
        }
        write(out, delim + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
