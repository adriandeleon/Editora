package com.editora.git;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.editora.git.GitStatus.FileEntry;

/**
 * Pure parser for {@code git status --porcelain=v2 --branch} output → a {@link GitStatus}.
 *
 * <p>Handles the branch headers ({@code # branch.head}, {@code # branch.upstream},
 * {@code # branch.ab +A -B}), ordinary changed entries ({@code 1 <XY> …}), renames/copies
 * ({@code 2 <XY> … <path>\t<orig>}), and untracked ({@code ? <path>}); ignored ({@code ! …}) lines
 * are skipped. No process work happens here, so it is unit-testable without a repo.
 */
public final class StatusParser {

    private StatusParser() {}

    public static GitStatus parse(String porcelain) {
        String branch = "";
        String upstream = "";
        int ahead = 0;
        int behind = 0;
        List<FileEntry> files = new ArrayList<>();

        if (porcelain == null) {
            return new GitStatus(true, branch, upstream, ahead, behind, files);
        }
        for (String line : porcelain.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            char kind = line.charAt(0);
            switch (kind) {
                case '#' -> {
                    if (line.startsWith("# branch.head ")) {
                        branch = line.substring("# branch.head ".length()).trim();
                    } else if (line.startsWith("# branch.upstream ")) {
                        upstream = line.substring("# branch.upstream ".length()).trim();
                    } else if (line.startsWith("# branch.ab ")) {
                        for (String tok :
                                line.substring("# branch.ab ".length()).trim().split("\\s+")) {
                            if (tok.startsWith("+")) {
                                ahead = parseInt(tok.substring(1));
                            } else if (tok.startsWith("-")) {
                                behind = parseInt(tok.substring(1));
                            }
                        }
                    }
                }
                case '1' -> {
                    // 1 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <path>
                    String[] f = line.split(" ", 9);
                    if (f.length == 9 && f[1].length() == 2) {
                        files.add(new FileEntry(unquotePath(f[8]), f[1].charAt(0), f[1].charAt(1), null));
                    }
                }
                case '2' -> {
                    // 2 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <Xscore> <path>\t<origPath>
                    String[] f = line.split(" ", 10);
                    if (f.length == 10 && f[1].length() == 2) {
                        String rest = f[9];
                        // The path/orig separator is a literal tab; a tab *inside* a name is C-quoted as
                        // "\t" (two chars), so this split never lands inside a quoted name.
                        int tab = rest.indexOf('\t');
                        String path = tab >= 0 ? rest.substring(0, tab) : rest;
                        String orig = tab >= 0 ? rest.substring(tab + 1) : null;
                        files.add(new FileEntry(unquotePath(path), f[1].charAt(0), f[1].charAt(1), unquotePath(orig)));
                    }
                }
                case '?' -> {
                    if (line.length() > 2) {
                        files.add(new FileEntry(unquotePath(line.substring(2)), '?', '?', null));
                    }
                }
                default -> {
                    // '!' ignored entries and anything else: skip.
                }
            }
        }
        return new GitStatus(true, branch, upstream, ahead, behind, files);
    }

    /**
     * Decodes git's C-style quoted path back to the real name. With {@code core.quotePath=true} (the default),
     * {@code git status --porcelain} (no {@code -z}) wraps a path containing non-ASCII/control/quote/backslash
     * bytes in double-quotes and escapes those bytes — {@code café.txt} → {@code "caf\303\251.txt"}. An
     * unquoted field (no surrounding quotes) is returned unchanged. The octal escapes are the raw UTF-8 bytes,
     * so they are collected and decoded as UTF-8. Returns {@code null} unchanged (rename orig may be absent).
     */
    static String unquotePath(String field) {
        if (field == null || field.length() < 2 || field.charAt(0) != '"' || field.charAt(field.length() - 1) != '"') {
            return field; // not quoted
        }
        String s = field.substring(1, field.length() - 1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                bytes.write(c); // a literal (printable ASCII) byte
                continue;
            }
            char e = s.charAt(++i);
            if (e >= '0' && e <= '7') { // \NNN octal byte (1-3 digits)
                int val = e - '0';
                for (int k = 0; k < 2 && i + 1 < s.length() && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '7'; k++) {
                    val = (val << 3) | (s.charAt(++i) - '0');
                }
                bytes.write(val & 0xFF);
            } else {
                bytes.write(
                        switch (e) {
                            case 'a' -> 7;
                            case 'b' -> 8;
                            case 't' -> 9;
                            case 'n' -> 10;
                            case 'v' -> 11;
                            case 'f' -> 12;
                            case 'r' -> 13;
                            default -> e; // \" \\ and any other escaped char → the char itself
                        });
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
