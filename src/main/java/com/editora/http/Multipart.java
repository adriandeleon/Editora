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
 * {@code {{vars}}} in inline parts, part headers and file paths (and in {@code <@ ./file} parts), and slurping
 * file parts from the request file's directory. The split is
 * unit-tested; {@link #build} is verified on its byte output.
 */
public final class Multipart {

    /** One multipart section: its {@code headers}, the {@code inlineBody} (when authored inline), or a
     *  {@code filePath} (relative to the request file) to slurp instead — with its {@code {{vars}}}
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

    /** Assembles the multipart wire bytes; inline parts pass through {@code subst} and file parts are read
     *  from {@code baseDir}. Best-effort — a missing file leaves its part empty rather than throwing. */
    public static byte[] build(List<Part> parts, String boundary, Path baseDir, Function<String, String> subst) {
        return build(parts, boundary, baseDir, subst, new ArrayList<>());
    }

    /**
     * As {@link #build(List, String, Path, Function)}, but reports each file part that could not be read
     * (missing, unreadable, or outside the request file's folder) in {@code problems}, so the caller can
     * refuse to send a form whose upload is silently empty.
     */
    public static byte[] build(
            List<Part> parts, String boundary, Path baseDir, Function<String, String> subst, List<String> problems) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String delim = "--" + boundary;
        for (Part p : parts) {
            write(out, delim + "\r\n");
            for (Map.Entry<String, String> h : p.headers().entrySet()) {
                // Part headers take {{vars}} too: a filename="{{name}}" must not go out literally.
                String value = subst == null ? h.getValue() : subst.apply(h.getValue());
                write(out, h.getKey() + ": " + value + "\r\n");
            }
            write(out, "\r\n");
            if (p.filePath() != null) {
                String path = subst == null ? p.filePath() : subst.apply(p.filePath());
                try {
                    // contained: a file part must not read outside the request file's own folder
                    Path file = HttpPaths.contained(baseDir, path);
                    if (file == null) {
                        problems.add("multipart file is outside the request folder: " + path);
                    } else if (p.substituteFile() && subst != null) {
                        write(out, subst.apply(Files.readString(file, StandardCharsets.UTF_8)));
                    } else {
                        out.writeBytes(Files.readAllBytes(file));
                    }
                } catch (Exception e) {
                    problems.add("multipart file not found: " + path); // the part stays empty
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
