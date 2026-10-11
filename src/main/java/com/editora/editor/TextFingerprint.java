package com.editora.editor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The SHA-256 of a text's UTF-16 code units, as 64 hex characters: what the saved baseline of a document keeps
 * instead of the document ({@link SavedBaseline}).
 *
 * <p>Hashed over the chars themselves, not an encoding of them, so the answer depends on nothing but the text —
 * not on the file's charset or line ending, which the bytes on disk do. The digest is fed in fixed chunks:
 * a 50 MB document is never copied into a byte array to be hashed.
 */
public final class TextFingerprint {

    private static final int CHUNK_CHARS = 4096;

    private TextFingerprint() {}

    /** The fingerprint of {@code text}, which is read once, left to right. */
    public static String of(CharSequence text) {
        MessageDigest digest = sha256();
        byte[] chunk = new byte[2 * CHUNK_CHARS];
        int filled = 0;
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            chunk[filled++] = (byte) (c >>> 8);
            chunk[filled++] = (byte) c;
            if (filled == chunk.length) {
                digest.update(chunk, 0, filled);
                filled = 0;
            }
        }
        digest.update(chunk, 0, filled);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
