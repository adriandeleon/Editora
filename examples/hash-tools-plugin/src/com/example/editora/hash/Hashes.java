package com.example.editora.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Pure cryptographic-digest helpers → lowercase hex. */
final class Hashes {

    private Hashes() {
    }

    /** {@code algorithm} is a JDK {@link MessageDigest} name, e.g. {@code MD5}/{@code SHA-1}/{@code SHA-256}. */
    static String hash(String algorithm, String input) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm)
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("missing digest " + algorithm, e);
        }
    }
}
