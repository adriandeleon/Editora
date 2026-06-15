package com.editora.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP Digest access authentication (RFC 2617) for the {@code Authorization: Digest user pass} shorthand:
 * the executor sends once, and on a {@code 401} with a {@code WWW-Authenticate: Digest …} challenge computes
 * the response header here and resends. MD5 comes from {@code java.security} (no dependency). The challenge
 * parse + the response computation are pure, so they are unit-tested against the RFC 2617 §3.5 vector.
 */
public final class DigestAuth {

    private static final Pattern PARAM = Pattern.compile("(\\w+)\\s*=\\s*(\"[^\"]*\"|[^,]+)");

    private DigestAuth() {}

    /** The user/pass of a {@code Digest user pass} shorthand header value, or {@code null}. */
    public record Credentials(String user, String pass) {}

    /** Parses {@code Digest user pass} (two whitespace tokens) into credentials, else {@code null}. */
    public static Credentials shorthand(String authValue) {
        if (authValue == null) {
            return null;
        }
        String v = authValue.strip();
        if (!v.regionMatches(true, 0, "Digest ", 0, 7)) {
            return null;
        }
        String[] parts = v.substring(7).strip().split("\\s+");
        return parts.length == 2 ? new Credentials(parts[0], parts[1]) : null;
    }

    /** Parses a {@code WWW-Authenticate: Digest …} challenge into its {@code key → value} parameters. */
    public static Map<String, String> parseChallenge(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        if (header == null) {
            return out;
        }
        String h = header.strip();
        int sp = h.indexOf(' ');
        if (sp > 0 && h.regionMatches(true, 0, "Digest", 0, 6)) {
            h = h.substring(sp + 1);
        }
        Matcher m = PARAM.matcher(h);
        while (m.find()) {
            out.put(
                    m.group(1).toLowerCase(java.util.Locale.ROOT),
                    unquote(m.group(2).strip()));
        }
        return out;
    }

    /**
     * The {@code Authorization: Digest …} header value for {@code creds} answering {@code challenge} for a
     * {@code method} request to {@code uri} (path+query). Supports {@code qop=auth} (with {@code nc}/{@code cnonce})
     * and the legacy no-qop form; algorithm MD5.
     */
    public static String authorization(
            Credentials creds, String method, String uri, Map<String, String> challenge, String cnonce, String nc) {
        String realm = challenge.getOrDefault("realm", "");
        String nonce = challenge.getOrDefault("nonce", "");
        String opaque = challenge.get("opaque");
        String qop = challenge.get("qop");
        boolean useQop = qop != null && (qop.equals("auth") || qop.contains("auth"));

        String ha1 = md5(creds.user() + ":" + realm + ":" + creds.pass());
        String ha2 = md5(method + ":" + uri);
        String response = useQop
                ? md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)
                : md5(ha1 + ":" + nonce + ":" + ha2);

        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(creds.user()).append("\", ");
        sb.append("realm=\"").append(realm).append("\", ");
        sb.append("nonce=\"").append(nonce).append("\", ");
        sb.append("uri=\"").append(uri).append("\", ");
        if (useQop) {
            sb.append("qop=auth, nc=")
                    .append(nc)
                    .append(", cnonce=\"")
                    .append(cnonce)
                    .append("\", ");
        }
        sb.append("response=\"").append(response).append("\"");
        if (opaque != null) {
            sb.append(", opaque=\"").append(opaque).append("\"");
        }
        sb.append(", algorithm=MD5");
        return sb.toString();
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
