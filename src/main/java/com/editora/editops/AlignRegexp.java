package com.editora.editops;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Emacs {@code align-regexp}: line up a column across several lines by padding spaces before a regexp.
 * Pure and toolkit-free (mirroring {@link LineTransforms}).
 *
 * <p>For each line the first match of the regexp is found; every matching line is then padded with spaces
 * <em>before</em> its match so all the matches start at the same column — the widest match-start across
 * the block. Lines with no match are left untouched. Because it only ever <b>adds</b> spaces (it never
 * removes existing whitespace) it is idempotent: aligning already-aligned text is a no-op.
 *
 * <p>This is the common case — aligning {@code =}, {@code :}, trailing {@code //} comments — where each
 * line already has consistent spacing before the separator and only the left-hand side differs in width.
 * Emacs' fuller behaviour (collapsing the whitespace group first, aligning a chosen capture group) is
 * deliberately not attempted. Columns are character columns, so a tab before the match is counted as one.
 */
public final class AlignRegexp {

    private AlignRegexp() {}

    /** Aligns the first match of {@code regex} on each line of {@code text}. Bad regex → text unchanged. */
    public static String align(String text, String regex) {
        if (text == null || regex == null || regex.isEmpty()) {
            return text;
        }
        Pattern p;
        try {
            p = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        int[] matchCol = new int[lines.length];
        int target = -1;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = p.matcher(lines[i]);
            if (m.find()) {
                matchCol[i] = m.start();
                target = Math.max(target, m.start());
            } else {
                matchCol[i] = -1;
            }
        }
        if (target < 0) {
            return text; // nothing matched — nothing to align
        }
        StringBuilder sb = new StringBuilder(text.length() + lines.length);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            if (matchCol[i] < 0 || matchCol[i] == target) {
                sb.append(line);
                continue;
            }
            sb.append(line, 0, matchCol[i]);
            sb.append(" ".repeat(target - matchCol[i]));
            sb.append(line, matchCol[i], line.length());
        }
        return sb.toString();
    }
}
