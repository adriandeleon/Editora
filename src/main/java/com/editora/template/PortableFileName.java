package com.editora.template;

import java.util.Locale;
import java.util.Set;

import com.editora.editor.LanguageRegistry;

/**
 * "Can this be a file name on every platform Editora runs on?" — the one definition shared by the
 * new-file prompt, template target paths and template ids.
 *
 * <p>The rules are Windows' (the strictest of the three), applied on every OS: a project created on Linux
 * with a file called {@code aux.md} or {@code notes?.txt} cannot be checked out on a colleague's Windows
 * machine, and a name with a control character is a mistake everywhere. Pure — unit-tested.
 */
public final class PortableFileName {

    private PortableFileName() {}

    /** Device names Windows reserves, with or without an extension ({@code CON}, {@code con.txt}). */
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "lpt1",
            "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** Common extensions the language registry does not know as a language but that are clearly a type. */
    private static final Set<String> EXTRA_EXTENSIONS = Set.of(
            "txt",
            "text",
            "log",
            "csv",
            "tsv",
            "ini",
            "cfg",
            "conf",
            "properties",
            "env",
            "lock",
            "bak",
            "tmp",
            "pdf",
            "png",
            "jpg",
            "jpeg",
            "gif",
            "webp",
            "ico",
            "zip",
            "gz",
            "tar",
            "jar",
            "class",
            "exe",
            "dll",
            "so",
            "bin",
            "rtf",
            "doc",
            "docx",
            "xls",
            "xlsx",
            "ppt",
            "pptx",
            "odt",
            "ipynb",
            "bat",
            "cmd",
            "ps1",
            "rst",
            "tex",
            "adoc",
            "org");

    /** Why a path segment cannot be used, or {@link #OK}. */
    public enum Problem {
        OK,
        /** Empty, {@code .} or {@code ..}. */
        NOT_A_NAME,
        /** A control character or one of {@code < > : " | ? * \ /}. */
        ILLEGAL_CHARACTER,
        /** A Windows device name ({@code CON}, {@code NUL}, {@code COM1}, …). */
        RESERVED_NAME,
        /** Ends with a dot or a space, which Windows silently strips. */
        TRAILING_DOT_OR_SPACE,
        /** {@code ~}: a shell's home shorthand, which would create a folder literally called {@code ~}. */
        HOME_SHORTHAND
    }

    /** Checks one path segment (a file or folder name, never a path). */
    public static Problem check(String segment) {
        if (segment == null || segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            return Problem.NOT_A_NAME;
        }
        if (segment.equals("~")) {
            return Problem.HOME_SHORTHAND;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < 0x20 || c == 0x7f || "<>:\"|?*\\/".indexOf(c) >= 0) {
                return Problem.ILLEGAL_CHARACTER;
            }
        }
        char last = segment.charAt(segment.length() - 1);
        if (last == '.' || last == ' ') {
            return Problem.TRAILING_DOT_OR_SPACE;
        }
        if (isWindowsReserved(segment)) {
            return Problem.RESERVED_NAME;
        }
        return Problem.OK;
    }

    /** True when {@code segment} is usable as a file or folder name on every platform. */
    public static boolean isPortable(String segment) {
        return check(segment) == Problem.OK;
    }

    /** True for a Windows device name, with or without an extension ({@code nul}, {@code COM1.txt}). */
    public static boolean isWindowsReserved(String segment) {
        int dot = segment.indexOf('.');
        String stem = (dot < 0 ? segment : segment.substring(0, dot)).trim().toLowerCase(Locale.ROOT);
        return WINDOWS_RESERVED.contains(stem);
    }

    /**
     * True when {@code extension} (no dot) names a file type — a language Editora recognizes, a type on the
     * New menu, or one of the common document/binary types. A purely numeric suffix is never one, so the
     * {@code 2} of {@code release-1.2} is part of the name rather than its type.
     */
    public static boolean isKnownExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return false;
        }
        String ext = extension.toLowerCase(Locale.ROOT);
        boolean letter = false;
        for (int i = 0; i < ext.length(); i++) {
            char c = ext.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '+') {
                return false;
            }
            letter |= Character.isLetter(c);
        }
        if (!letter) {
            return false;
        }
        if (EXTRA_EXTENSIONS.contains(ext) || NewFileCatalog.extensions().contains(ext)) {
            return true;
        }
        return !LanguageRegistry.PLAINTEXT.equals(LanguageRegistry.forFileName("x." + ext));
    }
}
