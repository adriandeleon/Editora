package com.editora.crypto;

import java.util.List;
import java.util.Locale;

/**
 * One OpenPGP key from the user's keyring, as {@code gpg --with-colons} lists it: the primary key's fingerprint
 * and long id, its user ids, the validity gpg computed for it, its capabilities, whether a secret key for it is
 * on this machine, and its subkeys (an encrypted file names the <em>encryption subkey</em>, never the primary).
 *
 * @param validity     gpg's validity letter for the key: {@code u} ultimate, {@code f} full, {@code m} marginal,
 *                     {@code -}/{@code q}/{@code o} unknown, {@code n} never, {@code e} expired, {@code r}
 *                     revoked, {@code d} disabled, {@code i} invalid
 * @param capabilities the key's capability letters; an upper-case letter is a capability of the whole key that
 *                     is usable now ({@code E} = it has a valid encryption subkey)
 * @param expires      seconds since the epoch, or 0 for never
 */
public record GpgKey(
        String fingerprint,
        String keyId,
        List<String> userIds,
        char validity,
        String capabilities,
        long expires,
        boolean secret,
        List<String> subkeyIds,
        List<String> subkeyFingerprints) {

    /** Why a key cannot be chosen as a recipient. */
    public enum Unusable {
        REVOKED,
        EXPIRED,
        DISABLED,
        INVALID,
        /** A signing-only key: it has no encryption subkey. */
        NO_ENCRYPTION
    }

    public GpgKey {
        userIds = List.copyOf(userIds);
        subkeyIds = List.copyOf(subkeyIds);
        subkeyFingerprints = List.copyOf(subkeyFingerprints);
        capabilities = capabilities == null ? "" : capabilities;
    }

    /** The first user id ({@code Name (comment) <email>}), or an empty string for a key without one. */
    public String primaryUserId() {
        return userIds.isEmpty() ? "" : userIds.get(0);
    }

    /** The name part of the primary user id, else its email, else the short key id. */
    public String name() {
        String uid = primaryUserId();
        int lt = uid.indexOf('<');
        String name = (lt >= 0 ? uid.substring(0, lt) : uid).strip();
        int paren = name.indexOf(" (");
        if (paren > 0 && name.endsWith(")")) {
            name = name.substring(0, paren).strip(); // drop "(comment)"
        }
        if (!name.isEmpty()) {
            return name;
        }
        String email = email();
        return email.isEmpty() ? shortId() : email;
    }

    /** The email address of the primary user id, or an empty string. */
    public String email() {
        String uid = primaryUserId();
        int lt = uid.lastIndexOf('<');
        int gt = uid.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            return uid.substring(lt + 1, gt).strip();
        }
        return uid.contains("@") && !uid.contains(" ") ? uid : "";
    }

    /** The last eight hex digits of the key id, as {@code 0x} notation names a key. */
    public String shortId() {
        return keyId.length() > 8 ? keyId.substring(keyId.length() - 8) : keyId;
    }

    /** Full or ultimate validity: the user (or someone they trust) has certified that this key is its owner's. */
    public boolean verified() {
        return validity == 'u' || validity == 'f';
    }

    /** Why this key cannot be a recipient, or null when it can. */
    public Unusable unusable() {
        return switch (validity) {
            case 'r' -> Unusable.REVOKED;
            case 'e' -> Unusable.EXPIRED;
            case 'd' -> Unusable.DISABLED;
            case 'i' -> Unusable.INVALID;
            default ->
                capabilities.indexOf('D') >= 0
                        ? Unusable.DISABLED
                        : capabilities.indexOf('E') < 0 ? Unusable.NO_ENCRYPTION : null;
        };
    }

    /** Whether this key can be chosen as a recipient now. */
    public boolean canEncrypt() {
        return unusable() == null;
    }

    /** Whether this key can sign: a secret key with the whole-key signing capability. */
    public boolean canSign() {
        return secret && capabilities.indexOf('S') >= 0 && validity != 'r' && validity != 'e';
    }

    /**
     * Whether {@code id} names this key: its fingerprint, its long id, or one of its subkeys' — the ids an
     * encrypted file lists are encryption subkeys. Case-insensitive; a 16-digit id matches a fingerprint's tail.
     */
    public boolean matches(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String want = id.strip().toUpperCase(Locale.ROOT);
        if (want.startsWith("0X")) {
            want = want.substring(2);
        }
        if (want.equalsIgnoreCase(fingerprint) || want.equalsIgnoreCase(keyId) || fingerprint.endsWith(want)) {
            return true;
        }
        for (String sub : subkeyIds) {
            if (want.equalsIgnoreCase(sub)) {
                return true;
            }
        }
        for (String sub : subkeyFingerprints) {
            if (want.equalsIgnoreCase(sub) || sub.endsWith(want)) {
                return true;
            }
        }
        return false;
    }

    /** The fingerprint in groups of four ({@code D2E7 9AE2 … F8C1 B87B}), the form people compare aloud. */
    public String groupedFingerprint() {
        return group(fingerprint);
    }

    /** The fingerprint shortened for a list row: first two groups, an ellipsis, the last four. */
    public String shortFingerprint() {
        if (fingerprint.length() <= 24) {
            return group(fingerprint);
        }
        int n = fingerprint.length();
        return group(fingerprint.substring(0, 8)) + " … " + group(fingerprint.substring(n - 16));
    }

    /** {@code hex} in groups of four, with a double space between the two halves of a 40-digit fingerprint. */
    public static String group(String hex) {
        if (hex == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 4) {
            if (i > 0) {
                sb.append(i == 20 && hex.length() == 40 ? "  " : " ");
            }
            sb.append(hex, i, Math.min(hex.length(), i + 4));
        }
        return sb.toString();
    }
}
