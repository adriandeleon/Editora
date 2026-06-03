package com.editora.git;

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

    private StatusParser() {
    }

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
                        for (String tok : line.substring("# branch.ab ".length()).trim().split("\\s+")) {
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
                        files.add(new FileEntry(f[8], f[1].charAt(0), f[1].charAt(1), null));
                    }
                }
                case '2' -> {
                    // 2 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <Xscore> <path>\t<origPath>
                    String[] f = line.split(" ", 10);
                    if (f.length == 10 && f[1].length() == 2) {
                        String rest = f[9];
                        int tab = rest.indexOf('\t');
                        String path = tab >= 0 ? rest.substring(0, tab) : rest;
                        String orig = tab >= 0 ? rest.substring(tab + 1) : null;
                        files.add(new FileEntry(path, f[1].charAt(0), f[1].charAt(1), orig));
                    }
                }
                case '?' -> {
                    if (line.length() > 2) {
                        files.add(new FileEntry(line.substring(2), '?', '?', null));
                    }
                }
                default -> {
                    // '!' ignored entries and anything else: skip.
                }
            }
        }
        return new GitStatus(true, branch, upstream, ahead, behind, files);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
