package com.editora.crypto;

import java.util.ArrayList;
import java.util.List;

/**
 * The machine-readable lines GnuPG writes to {@code --status-fd} ({@code [GNUPG:] KEYWORD args…}), parsed out of
 * its stderr. These keywords are stable across versions and locales, so every decision Editora makes about a gpg
 * run is taken from them — never from gpg's translated human messages. Pure.
 */
public final class GpgStatus {

    /** The status-line prefix. */
    static final String PREFIX = "[GNUPG:] ";

    /** One status line: its keyword and the space-separated arguments after it. */
    public record Line(String keyword, List<String> args) {
        public String arg(int i) {
            return i < args.size() ? args.get(i) : "";
        }
    }

    private final List<Line> lines;
    private final String human;

    private GpgStatus(List<Line> lines, String human) {
        this.lines = List.copyOf(lines);
        this.human = human;
    }

    /** Splits {@code stderr} into its status lines and gpg's human messages. */
    public static GpgStatus parse(String stderr) {
        List<Line> lines = new ArrayList<>();
        StringBuilder human = new StringBuilder();
        if (stderr != null) {
            for (String raw : stderr.split("\r?\n")) {
                if (raw.startsWith(PREFIX)) {
                    String[] parts = raw.substring(PREFIX.length()).strip().split(" ");
                    if (parts.length > 0 && !parts[0].isEmpty()) {
                        lines.add(new Line(parts[0], List.of(parts).subList(1, parts.length)));
                    }
                } else if (!raw.isBlank()) {
                    if (human.length() > 0) {
                        human.append('\n');
                    }
                    human.append(raw);
                }
            }
        }
        return new GpgStatus(lines, human.toString());
    }

    public List<Line> lines() {
        return lines;
    }

    /** gpg's own (translated, human) messages — for a Details view, never for decisions. */
    public String humanText() {
        return human;
    }

    public boolean has(String keyword) {
        return first(keyword) != null;
    }

    /** The first line with {@code keyword}, or null. */
    public Line first(String keyword) {
        for (Line l : lines) {
            if (l.keyword().equals(keyword)) {
                return l;
            }
        }
        return null;
    }

    public List<Line> all(String keyword) {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            if (l.keyword().equals(keyword)) {
                out.add(l);
            }
        }
        return out;
    }

    /** The key ids a message is encrypted to ({@code ENC_TO}), in order, without duplicates. */
    public List<String> encryptedTo() {
        List<String> ids = new ArrayList<>();
        for (Line l : all("ENC_TO")) {
            String id = l.arg(0);
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * The gpg error codes in {@code ERROR} / {@code FAILURE} lines, with the error source stripped (a code is
     * {@code source << 24 | code}; only the code says what happened).
     */
    public List<Integer> errorCodes() {
        List<Integer> codes = new ArrayList<>();
        for (Line l : lines) {
            if (l.keyword().equals("ERROR") || l.keyword().equals("FAILURE")) {
                // Usually a number; some say what it is too ("11_BAD_PASSPHRASE"): the leading digits count.
                String arg = l.arg(1);
                int end = 0;
                while (end < arg.length() && Character.isDigit(arg.charAt(end))) {
                    end++;
                }
                if (end > 0 && end <= 18) {
                    codes.add((int) (Long.parseLong(arg.substring(0, end)) & 0xFFFF));
                }
            }
        }
        return codes;
    }

    /**
     * The signature this run checked, or null when there was none: who made it and whether it is good. Taken
     * from {@code GOODSIG}/{@code BADSIG}/{@code EXPSIG}/{@code EXPKEYSIG}/{@code REVKEYSIG}/{@code ERRSIG}, and
     * {@code VALIDSIG} for the signing key's fingerprint.
     */
    public Signature signature() {
        for (Line l : lines) {
            Signature.State state = switch (l.keyword()) {
                case "GOODSIG" -> Signature.State.GOOD;
                case "BADSIG" -> Signature.State.BAD;
                case "EXPSIG" -> Signature.State.EXPIRED_SIGNATURE;
                case "EXPKEYSIG" -> Signature.State.EXPIRED_KEY;
                case "REVKEYSIG" -> Signature.State.REVOKED_KEY;
                case "ERRSIG" -> Signature.State.UNKNOWN_KEY;
                default -> null;
            };
            if (state == null) {
                continue;
            }
            String keyId = l.arg(0);
            String who = state == Signature.State.UNKNOWN_KEY
                    ? ""
                    : String.join(
                            " ",
                            l.args()
                                    .subList(
                                            Math.min(1, l.args().size()),
                                            l.args().size()));
            Line valid = first("VALIDSIG");
            String fpr = valid == null ? "" : valid.arg(9).isEmpty() ? valid.arg(0) : valid.arg(9);
            Line trust = firstOf("TRUST_ULTIMATE", "TRUST_FULLY");
            return new Signature(state, keyId, unescapePercent(who), fpr, trust != null);
        }
        return null;
    }

    /** Status lines carry user ids as UTF-8 with {@code %XX} escapes (a space would split the arguments). */
    static String unescapePercent(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    bytes.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 3;
                    continue;
                } catch (NumberFormatException e) {
                    // a literal percent sign
                }
            }
            int cp = s.codePointAt(i);
            byte[] chunk = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            bytes.write(chunk, 0, chunk.length);
            i += Character.charCount(cp);
        }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Line firstOf(String... keywords) {
        for (Line l : lines) {
            for (String k : keywords) {
                if (l.keyword().equals(k)) {
                    return l;
                }
            }
        }
        return null;
    }

    /** A checked signature. {@code verified} is gpg's full/ultimate validity for the signing key. */
    public record Signature(State state, String keyId, String signer, String fingerprint, boolean verified) {
        public enum State {
            GOOD,
            BAD,
            EXPIRED_SIGNATURE,
            EXPIRED_KEY,
            REVOKED_KEY,
            /** The signing key is not in the keyring, so the signature could not be checked. */
            UNKNOWN_KEY
        }
    }
}
