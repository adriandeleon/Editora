package com.editora.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses {@code gpg --list-keys --with-colons --with-subkey-fingerprints} (and the matching
 * {@code --list-secret-keys} listing, which says which keys are the user's own) into {@link GpgKey}s. Pure; the
 * colon format is gpg's stable machine interface ({@code doc/DETAILS} in GnuPG), unlike its human output.
 */
public final class KeyListing {

    private KeyListing() {}

    /**
     * The public keys in {@code publicColons}, each marked {@link GpgKey#secret()} when {@code secretColons}
     * lists a secret key with the same fingerprint. Either argument may be null or empty.
     */
    public static List<GpgKey> parse(String publicColons, String secretColons) {
        Set<String> secret = new HashSet<>();
        for (Builder b : blocks(secretColons, "sec")) {
            if (!b.fingerprint.isEmpty()) {
                secret.add(b.fingerprint);
            }
        }
        List<GpgKey> keys = new ArrayList<>();
        for (Builder b : blocks(publicColons, "pub")) {
            keys.add(b.build(secret.contains(b.fingerprint)));
        }
        return keys;
    }

    /** Accumulates one primary key's records until the next primary key starts. */
    private static final class Builder {
        String fingerprint = "";
        String keyId = "";
        char validity = '-';
        String capabilities = "";
        long expires;
        final List<String> uids = new ArrayList<>();
        final List<String> subIds = new ArrayList<>();
        final List<String> subFprs = new ArrayList<>();
        /** Which record the next {@code fpr} line belongs to. */
        boolean fprForSubkey;

        GpgKey build(boolean isSecret) {
            return new GpgKey(fingerprint, keyId, uids, validity, capabilities, expires, isSecret, subIds, subFprs);
        }
    }

    private static List<Builder> blocks(String colons, String primaryType) {
        List<Builder> out = new ArrayList<>();
        if (colons == null || colons.isEmpty()) {
            return out;
        }
        String subType = "pub".equals(primaryType) ? "sub" : "ssb";
        Builder current = null;
        for (String line : colons.split("\r?\n")) {
            String[] f = line.split(":", -1);
            String type = f[0];
            if (type.equals(primaryType)) {
                current = new Builder();
                out.add(current);
                current.validity = field(f, 1).isEmpty() ? '-' : field(f, 1).charAt(0);
                current.keyId = field(f, 4);
                current.expires = parseLong(field(f, 6));
                current.capabilities = field(f, 11);
                current.fprForSubkey = false;
            } else if (current == null) {
                continue; // "tru", "sec" in a public listing, …: nothing to attach to yet
            } else if (type.equals("fpr")) {
                String fpr = field(f, 9);
                if (current.fprForSubkey) {
                    current.subFprs.add(fpr);
                } else if (current.fingerprint.isEmpty()) {
                    current.fingerprint = fpr;
                }
            } else if (type.equals("uid")) {
                String uid = unescape(field(f, 9));
                char uidValidity = field(f, 1).isEmpty() ? '-' : field(f, 1).charAt(0);
                if (!uid.isEmpty() && uidValidity != 'r') { // a revoked user id is not who the key is now
                    current.uids.add(uid);
                }
            } else if (type.equals(subType)) {
                current.subIds.add(field(f, 4));
                current.fprForSubkey = true;
            }
        }
        return out;
    }

    private static String field(String[] f, int i) {
        return i < f.length ? f[i] : "";
    }

    private static long parseLong(String s) {
        try {
            return s.isEmpty() ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0; // an ISO date (gpg's --fixed-list-mode off on old versions): treat as "unknown"
        }
    }

    /**
     * Undoes the colon format's escaping of a user id: {@code \xNN} is a byte (a colon, a control character, or
     * a byte of a UTF-8 sequence), and the bytes together are UTF-8.
     */
    static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '\\' && i + 3 < s.length() && s.charAt(i + 1) == 'x') {
                try {
                    bytes.write(Integer.parseInt(s.substring(i + 2, i + 4), 16));
                    i += 4;
                    continue;
                } catch (NumberFormatException e) {
                    // not an escape after all: keep the backslash
                }
            }
            int cp = s.codePointAt(i);
            byte[] chunk = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            bytes.write(chunk, 0, chunk.length);
            i += Character.charCount(cp);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
