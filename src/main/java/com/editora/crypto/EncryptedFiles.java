package com.editora.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.editora.text.EncryptedNames;
import org.jspecify.annotations.Nullable;

/**
 * Names and bytes of OpenPGP-encrypted files: which file names mean "encrypted" ({@code .gpg}, {@code .pgp},
 * {@code .asc}), the inner name that decides a file's language ({@code notes.md.gpg} is Markdown), and whether
 * bytes really are an encrypted message — a {@code .asc} file is just as often a public key or a signature,
 * which open as ordinary text. Pure.
 */
public final class EncryptedFiles {

    private EncryptedFiles() {}

    /** The extensions of encrypted files, in the order Editora offers them. */
    public static final List<String> EXTENSIONS = EncryptedNames.EXTENSIONS;

    /** What the start of a file is. */
    public enum Kind {
        /** Not OpenPGP at all. */
        NONE,
        /** {@code -----BEGIN PGP MESSAGE-----}: an encrypted (or signed-only) message, ASCII armored. */
        ARMORED_MESSAGE,
        /** Armor of another kind: a public or private key, a signature, a clear-signed text. */
        ARMORED_OTHER,
        /** A binary OpenPGP message that starts with a session-key packet: encrypted. */
        BINARY_MESSAGE
    }

    static final String BEGIN_MESSAGE = "-----BEGIN PGP MESSAGE-----";
    static final String END_MESSAGE = "-----END PGP MESSAGE-----";
    static final String BEGIN_PUBLIC_KEY = "-----BEGIN PGP PUBLIC KEY BLOCK-----";
    static final String BEGIN_SIGNED = "-----BEGIN PGP SIGNED MESSAGE-----";

    /** Whether {@code fileName} ends in an encryption extension. */
    public static boolean hasEncryptionExtension(String fileName) {
        return EncryptedNames.extensionOf(fileName) != null;
    }

    /** The encryption extension of {@code fileName} ({@code "gpg"}, …), lower case, or null. */
    public static @Nullable String extensionOf(String fileName) {
        return EncryptedNames.extensionOf(fileName);
    }

    /** {@code notes.md.gpg} → {@code notes.md}; a name without an encryption extension is returned as it is. */
    public static String innerName(String fileName) {
        return EncryptedNames.innerName(fileName);
    }

    /** The name that decides a file's language and icon (see {@link EncryptedNames#languageName}). */
    public static String languageName(String fileName) {
        return EncryptedNames.languageName(fileName);
    }

    /** {@code file} with {@code .gpg} (or {@code extension}) appended: where Encrypt File writes. */
    public static Path encryptedSibling(Path file, String extension) {
        return file.resolveSibling(file.getFileName().toString() + "." + extension);
    }

    /** {@code file} without its encryption extension: where Remove Encryption suggests writing. */
    public static Path plainSibling(Path file) {
        return file.resolveSibling(innerName(file.getFileName().toString()));
    }

    /** Whether a new file named {@code fileName} should be written ASCII-armored ({@code .asc}). */
    public static boolean armoredByName(String fileName) {
        return "asc".equals(extensionOf(fileName));
    }

    /** Classifies the start of a file ({@code head} need only hold the first kilobyte). */
    public static Kind sniff(byte[] head) {
        if (head == null || head.length == 0) {
            return Kind.NONE;
        }
        int first = head[0] & 0xFF;
        if ((first & 0x80) != 0) {
            // A binary packet header: new format keeps the tag in the low 6 bits, the old format in bits 2-5.
            int tag = (first & 0x40) != 0 ? first & 0x3F : (first >> 2) & 0x0F;
            // 1 = public-key encrypted session key, 3 = symmetric-key encrypted session key: an encrypted message.
            return tag == 1 || tag == 3 ? Kind.BINARY_MESSAGE : Kind.NONE;
        }
        String text = new String(head, 0, Math.min(head.length, 1024), StandardCharsets.ISO_8859_1);
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (!text.startsWith("-----BEGIN PGP ", i)) {
            return Kind.NONE;
        }
        return text.startsWith(BEGIN_MESSAGE, i) ? Kind.ARMORED_MESSAGE : Kind.ARMORED_OTHER;
    }

    /**
     * Whether a file is one Editora decrypts on open: an encryption extension <em>and</em> encrypted bytes. A
     * {@code .asc} public key, or a {@code .gpg} file that is really plain text, opens as text.
     */
    public static boolean isEncryptedFile(String fileName, byte[] head) {
        Kind kind = sniff(head);
        return hasEncryptionExtension(fileName) && (kind == Kind.ARMORED_MESSAGE || kind == Kind.BINARY_MESSAGE);
    }

    /** Whether {@code text} (a file's start) is an armored public key — what Import Key offers to read. */
    public static boolean isArmoredPublicKey(String text) {
        return text != null && text.stripLeading().startsWith(BEGIN_PUBLIC_KEY);
    }

    /** Whether {@code text} is a clear-signed message. */
    public static boolean isClearSigned(String text) {
        return text != null && text.stripLeading().startsWith(BEGIN_SIGNED);
    }

    /** An armored message inside a larger text: its character range, end exclusive (after the END line). */
    public record Block(int start, int end) {}

    /**
     * The armored message that contains {@code offset} (or whose BEGIN line it is on), or null. For the
     * "Decrypt Block" action in an ordinary document: a message pasted into a note or an email draft.
     */
    public static @Nullable Block blockAt(String text, int offset) {

        if (text == null || text.isEmpty()) {
            return null;
        }
        int clamped = Math.max(0, Math.min(offset, text.length()));
        int lineEnd = text.indexOf('\n', clamped);
        int searchFrom = lineEnd < 0 ? text.length() : lineEnd;
        int begin = text.lastIndexOf(BEGIN_MESSAGE, searchFrom);
        if (begin < 0) {
            return null;
        }
        int end = text.indexOf(END_MESSAGE, begin);
        if (end < 0) {
            return null;
        }
        int stop = end + END_MESSAGE.length();
        if (clamped > stop) {
            return null; // the caret is after this block, not inside it
        }
        return new Block(begin, stop);
    }
}
