package com.editora.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP Digest access authentication (RFC 7616, and the RFC 2617 forms it keeps) for the
 * {@code Authorization: Digest user pass} shorthand: the executor sends once, and on a {@code 401} with
 * {@code WWW-Authenticate: Digest …} challenges computes the response header here and resends.
 *
 * <p>Algorithms {@code MD5}, {@code SHA-256} and {@code SHA-512-256}, each also as {@code -sess}, all from
 * {@code java.security}. {@code qop=auth} is preferred; {@code qop=auth-int} (which hashes the request body) is
 * answered when it is the only one offered; a challenge without {@code qop} gets the legacy RFC 2069 answer. A
 * server that offers several challenges (one per header, or several in one header) is answered with the
 * strongest one this class supports. Pure, so it is unit-tested against the RFC 2617 §3.5 and RFC 7616 §3.9.1
 * vectors.
 */
public final class DigestAuth {

    private static final Pattern PARAM = Pattern.compile("(\\w+)\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^,]+)");

    /** Where one challenge ends and the next scheme begins inside a single header value. */
    private static final Pattern NEXT_CHALLENGE =
            Pattern.compile("(?i),\\s*(?=(?:Digest|Basic|Bearer|Negotiate|NTLM|Mutual|HOBA|SCRAM-[\\w-]+)\\s)");

    /** Supported algorithms, weakest first; the index is the preference. */
    private static final List<String> ALGORITHMS = List.of("MD5", "SHA-256", "SHA-512-256");

    private DigestAuth() {}

    /** The user/pass of a {@code Digest user pass} shorthand header value, or {@code null}. */
    public record Credentials(String user, String pass) {}

    /**
     * Parses {@code Digest user pass} into credentials, else {@code null}. The first word is the user and the rest
     * of the value the password, so a password may contain spaces. A real Digest header ({@code Digest
     * username="…", …}) is not shorthand.
     */
    public static Credentials shorthand(String authValue) {
        if (authValue == null) {
            return null;
        }
        String v = authValue.strip();
        if (!v.regionMatches(true, 0, "Digest ", 0, 7)) {
            return null;
        }
        String rest = v.substring(7).strip();
        int space = HttpAuth.indexOfWhitespace(rest);
        if (space <= 0) {
            return null;
        }
        String user = rest.substring(0, space);
        if (user.contains("=") || user.contains("\"") || user.contains(",")) {
            return null; // username="…", realm=… — a computed header, sent as written
        }
        return new Credentials(user, rest.substring(space).strip());
    }

    /** Parses one {@code WWW-Authenticate: Digest …} challenge into its {@code key → value} parameters. */
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
            out.put(m.group(1).toLowerCase(Locale.ROOT), unquote(m.group(2).strip()));
        }
        return out;
    }

    /**
     * The strongest Digest challenge this class can answer among all the {@code WWW-Authenticate} header
     * values, or {@code null} when there is none (no Digest challenge, or only unsupported algorithms or qops).
     */
    public static Map<String, String> strongest(List<String> wwwAuthenticate) {
        Map<String, String> best = null;
        int bestRank = -1;
        for (String header : wwwAuthenticate == null ? List.<String>of() : wwwAuthenticate) {
            for (String challenge : splitChallenges(header)) {
                if (!challenge.regionMatches(true, 0, "Digest ", 0, 7)) {
                    continue;
                }
                Map<String, String> params = parseChallenge(challenge);
                int rank = rank(params);
                if (rank > bestRank) {
                    best = params;
                    bestRank = rank;
                }
            }
        }
        return best;
    }

    /** Whether any of the header values offers a Digest challenge at all (supported or not). */
    public static boolean offersDigest(List<String> wwwAuthenticate) {
        for (String header : wwwAuthenticate == null ? List.<String>of() : wwwAuthenticate) {
            for (String challenge : splitChallenges(header)) {
                if (challenge.regionMatches(true, 0, "Digest ", 0, 7)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> splitChallenges(String header) {
        List<String> out = new ArrayList<>();
        if (header == null) {
            return out;
        }
        for (String part : NEXT_CHALLENGE.split(header.strip())) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    /** Preference of a challenge: algorithm strength, plain {@code auth} over {@code auth-int}; -1 = unsupported. */
    private static int rank(Map<String, String> challenge) {
        int alg = ALGORITHMS.indexOf(baseAlgorithm(challenge));
        String qop = chooseQop(challenge.get("qop"));
        if (alg < 0 || "?".equals(qop)) {
            return -1;
        }
        return alg * 2 + ("auth-int".equals(qop) ? 0 : 1);
    }

    private static String algorithmToken(Map<String, String> challenge) {
        String a = challenge.get("algorithm");
        return a == null || a.isBlank() ? "MD5" : a.strip();
    }

    private static String baseAlgorithm(Map<String, String> challenge) {
        String a = algorithmToken(challenge).toUpperCase(Locale.ROOT);
        return a.endsWith("-SESS") ? a.substring(0, a.length() - 5) : a;
    }

    /** {@code auth} when offered, else {@code auth-int}, null for a challenge without qop, "?" for none usable. */
    private static String chooseQop(String qop) {
        if (qop == null || qop.isBlank()) {
            return null;
        }
        boolean authInt = false;
        for (String q : qop.split(",")) {
            String t = q.strip().toLowerCase(Locale.ROOT);
            if (t.equals("auth")) {
                return "auth";
            }
            authInt |= t.equals("auth-int");
        }
        return authInt ? "auth-int" : "?";
    }

    /**
     * The {@code Authorization: Digest …} header value for {@code creds} answering {@code challenge} for a
     * {@code method} request to {@code uri} (path+query), for a request without a body.
     */
    public static String authorization(
            Credentials creds, String method, String uri, Map<String, String> challenge, String cnonce, String nc) {
        return authorization(creds, method, uri, challenge, cnonce, nc, new byte[0]);
    }

    /**
     * As {@link #authorization(Credentials, String, String, Map, String, String)}; {@code body} is hashed when the
     * challenge only offers {@code qop=auth-int}.
     */
    public static String authorization(
            Credentials creds,
            String method,
            String uri,
            Map<String, String> challenge,
            String cnonce,
            String nc,
            byte[] body) {
        String realm = challenge.getOrDefault("realm", "");
        String nonce = challenge.getOrDefault("nonce", "");
        String opaque = challenge.get("opaque");
        String qop = chooseQop(challenge.get("qop"));
        if ("?".equals(qop)) {
            qop = null;
        }
        String algorithm = algorithmToken(challenge);
        String digestName = digestName(baseAlgorithm(challenge));
        boolean sess = algorithm.toUpperCase(Locale.ROOT).endsWith("-SESS");

        String ha1 = hash(digestName, creds.user() + ":" + realm + ":" + creds.pass());
        if (sess) {
            ha1 = hash(digestName, ha1 + ":" + nonce + ":" + cnonce);
        }
        String a2 = method + ":" + uri;
        if ("auth-int".equals(qop)) {
            a2 += ":" + hash(digestName, body == null ? new byte[0] : body);
        }
        String ha2 = hash(digestName, a2);
        String response = qop != null
                ? hash(digestName, ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2)
                : hash(digestName, ha1 + ":" + nonce + ":" + ha2);

        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(quoted(creds.user())).append("\", ");
        sb.append("realm=\"").append(quoted(realm)).append("\", ");
        sb.append("nonce=\"").append(quoted(nonce)).append("\", ");
        sb.append("uri=\"").append(quoted(uri)).append("\", ");
        if (qop != null) {
            sb.append("qop=").append(qop).append(", nc=").append(nc).append(", cnonce=\"");
            sb.append(cnonce).append("\", ");
        }
        sb.append("response=\"").append(response).append("\"");
        if (opaque != null) {
            sb.append(", opaque=\"").append(quoted(opaque)).append("\"");
        }
        sb.append(", algorithm=").append(algorithm);
        return sb.toString();
    }

    private static String digestName(String base) {
        return switch (base) {
            case "SHA-256" -> "SHA-256";
            case "SHA-512-256" -> "SHA-512/256";
            default -> "MD5";
        };
    }

    /** Escapes a quoted-string value: {@code \} and {@code "} get a backslash. */
    private static String quoted(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replaceAll("\\\\(.)", "$1");
        }
        return v;
    }

    private static String hash(String algorithm, String s) {
        return hash(algorithm, s.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String algorithm, byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance(algorithm).digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("digest algorithm missing from the JDK: " + algorithm, e);
        }
    }
}
