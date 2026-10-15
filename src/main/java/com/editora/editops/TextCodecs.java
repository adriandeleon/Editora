package com.editora.editops;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Encode / decode and escape / unescape transforms for selected text: Base64, URL (percent-encoding), JSON
 * string escapes, HTML entities and {@code \}{@code uXXXX} escapes. Every decode is strict: input it cannot
 * read throws {@link IllegalArgumentException} with a short reason, so the caller changes nothing rather than
 * writing half-decoded text. Pure.
 */
public final class TextCodecs {

    private TextCodecs() {}

    // --- Base64 ---

    /** The UTF-8 bytes of {@code text} in standard Base64, with padding. */
    public static String base64Encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Standard or URL-safe Base64 (padding optional, whitespace ignored) decoded as UTF-8 text. Bytes that are
     * not UTF-8 text are refused: they would be saved back changed.
     */
    public static String base64Decode(String text) {
        String compact = text.replaceAll("\\s+", "");
        byte[] bytes;
        try {
            bytes = compact.indexOf('-') >= 0 || compact.indexOf('_') >= 0
                    ? Base64.getUrlDecoder().decode(compact)
                    : Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not Base64", e);
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("decodes to bytes that are not UTF-8 text", e);
        }
    }

    // --- URL ---

    /** Percent-encodes everything but unreserved characters; a space becomes {@code %20}, not {@code +}. */
    public static String urlEncode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~");
    }

    /** Decodes percent-escapes (and {@code +} as a space, as form data has it). */
    public static String urlDecode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("a % escape is incomplete or not hexadecimal", e);
        }
    }

    // --- JSON string escapes ---

    /** The text as the inside of a JSON string literal (no quotes added). */
    public static String jsonEscape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Reads JSON string escapes ({@code \n}, {@code \"}, {@code \}{@code u00e9}, …). */
    public static String jsonUnescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 1 >= text.length()) {
                throw new IllegalArgumentException("a \\ ends the text");
            }
            char e = text.charAt(++i);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) hex4(text, i + 1));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("\\" + e + " is not a JSON escape");
            }
        }
        return out.toString();
    }

    // --- \\uXXXX escapes ---

    /** Every character outside ASCII as {@code \}{@code uXXXX} (a character above U+FFFF as its two halves). */
    public static String unicodeEscape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                out.append(c);
            } else {
                out.append(String.format("\\u%04x", (int) c));
            }
        }
        return out.toString();
    }

    /** Reads {@code \}{@code uXXXX} escapes; every other character, backslashes included, stays as it is. */
    public static String unicodeUnescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == 'u') {
                out.append((char) hex4(text, i + 2));
                i += 5;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int hex4(String text, int at) {
        if (at + 4 > text.length()) {
            throw new IllegalArgumentException("a \\u escape needs four hex digits");
        }
        try {
            return Integer.parseInt(text.substring(at, at + 4), 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\\u" + text.substring(at, at + 4) + " is not hexadecimal", e);
        }
    }

    // --- HTML entities ---

    /** Escapes the five characters HTML reserves: {@code & < > " '}. */
    public static String htmlEscape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"),
            Map.entry("lt", "<"),
            Map.entry("gt", ">"),
            Map.entry("quot", "\""),
            Map.entry("apos", "'"),
            Map.entry("nbsp", " "),
            Map.entry("copy", "©"),
            Map.entry("reg", "®"),
            Map.entry("trade", "™"),
            Map.entry("hellip", "…"),
            Map.entry("mdash", "—"),
            Map.entry("ndash", "–"),
            Map.entry("lsquo", "‘"),
            Map.entry("rsquo", "’"),
            Map.entry("ldquo", "“"),
            Map.entry("rdquo", "”"),
            Map.entry("laquo", "«"),
            Map.entry("raquo", "»"),
            Map.entry("euro", "€"),
            Map.entry("pound", "£"),
            Map.entry("yen", "¥"),
            Map.entry("cent", "¢"),
            Map.entry("sect", "§"),
            Map.entry("deg", "°"),
            Map.entry("plusmn", "±"),
            Map.entry("times", "×"),
            Map.entry("divide", "÷"),
            Map.entry("middot", "·"),
            Map.entry("bull", "•"),
            Map.entry("para", "¶"),
            Map.entry("shy", "­"));

    /**
     * Reads named entities (the reserved five and the common typographic ones) and numeric ones
     * ({@code &#233;}, {@code &#x1F600;}). An entity it does not know is left as written.
     */
    public static String htmlUnescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int semi = c == '&' ? text.indexOf(';', i + 1) : -1;
            if (semi < 0 || semi - i > 12) {
                out.append(c);
                i++;
                continue;
            }
            String name = text.substring(i + 1, semi);
            String decoded = entity(name);
            if (decoded == null) {
                out.append(c);
                i++;
            } else {
                out.append(decoded);
                i = semi + 1;
            }
        }
        return out.toString();
    }

    private static String entity(String name) {
        if (name.startsWith("#")) {
            try {
                int cp = name.startsWith("#x") || name.startsWith("#X")
                        ? Integer.parseInt(name.substring(2), 16)
                        : Integer.parseInt(name.substring(1));
                return Character.isValidCodePoint(cp) ? Character.toString(cp) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return ENTITIES.get(name);
    }
}
