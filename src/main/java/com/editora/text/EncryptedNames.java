package com.editora.text;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The file names of OpenPGP-encrypted files: {@code .gpg}, {@code .pgp} and {@code .asc}, and the inner name
 * that decides an encrypted file's language — {@code notes.md.gpg} is Markdown. Pure, in the editor package so
 * the language registry can use it; {@code com.editora.crypto.EncryptedFiles} builds on it.
 */
public final class EncryptedNames {

    private EncryptedNames() {}

    /** The extensions of encrypted files, in the order Editora offers them. */
    public static final List<String> EXTENSIONS = List.of("gpg", "asc", "pgp");

    /** The encryption extension of {@code fileName} ({@code "gpg"}, …), lower case, or null. */
    public static @Nullable String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        String base = baseName(fileName);
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot == base.length() - 1) {
            return null;
        }
        String ext = base.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXTENSIONS.contains(ext) ? ext : null;
    }

    /** {@code notes.md.gpg} → {@code notes.md}; a name without an encryption extension is returned as it is. */
    public static String innerName(String fileName) {
        return extensionOf(fileName) == null ? fileName : fileName.substring(0, fileName.lastIndexOf('.'));
    }

    /**
     * The name that decides a file's language and icon: the inner name when it has an extension of its own
     * ({@code notes.md.asc} is Markdown), else the name itself ({@code key.asc} stays an {@code .asc} file).
     */
    public static String languageName(String fileName) {
        String inner = innerName(fileName);
        if (inner == null || inner.equals(fileName)) {
            return fileName;
        }
        return baseName(inner).lastIndexOf('.') > 0 ? inner : fileName;
    }

    static String baseName(String fileName) {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        return slash >= 0 ? fileName.substring(slash + 1) : fileName;
    }
}
