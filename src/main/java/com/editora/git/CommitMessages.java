package com.editora.git;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure rules about a commit message as it is typed: how long its subject is against the two conventional
 * marks, which body lines run past the wrap column, what git would keep of it under {@code --cleanup=strip},
 * and how a list of recently used messages is kept. Toolkit-free and unit-tested.
 */
public final class CommitMessages {

    private CommitMessages() {}

    /** A subject longer than this is getting long: the soft mark of the length guide. */
    public static final int SUBJECT_SOFT = 50;
    /** A subject longer than this is cut off by {@code git log --oneline} in most tools: the warning mark. */
    public static final int SUBJECT_HARD = 72;
    /** The column body text is conventionally wrapped at. */
    public static final int BODY_WIDTH = 72;
    /** How many recent messages are kept per repository. */
    public static final int HISTORY_MAX = 20;

    /** Where the subject stands against the two marks. */
    public enum SubjectLength {
        OK,
        /** Past {@link #SUBJECT_SOFT}. */
        LONG,
        /** Past {@link #SUBJECT_HARD}. */
        TOO_LONG
    }

    /**
     * What the length guide shows. Advice only: nothing here ever blocks a commit.
     *
     * @param subjectLength characters in the subject line (code points, not UTF-16 units)
     * @param longBodyLines body lines longer than {@link #BODY_WIDTH}
     */
    public record Guide(int subjectLength, SubjectLength subject, int longBodyLines) {}

    /** The guide for {@code text}; lines that begin with {@code commentChar} are not part of the message. */
    public static Guide guide(String text, char commentChar) {
        int subjectLength = -1;
        int longBody = 0;
        for (String line : lines(text)) {
            if (isComment(line, commentChar)) {
                continue;
            }
            String trimmed = line.stripTrailing();
            int length = trimmed.codePointCount(0, trimmed.length());
            if (subjectLength < 0) {
                if (!trimmed.isBlank()) {
                    subjectLength = length;
                }
            } else if (length > BODY_WIDTH) {
                longBody++;
            }
        }
        int subject = Math.max(0, subjectLength);
        SubjectLength mark = subject > SUBJECT_HARD
                ? SubjectLength.TOO_LONG
                : subject > SUBJECT_SOFT ? SubjectLength.LONG : SubjectLength.OK;
        return new Guide(subject, mark, longBody);
    }

    /**
     * {@code text} as {@code git commit --cleanup=strip} would record it: comment lines removed, trailing
     * white space cut from every line, runs of blank lines collapsed to one, and no blank line first or last.
     * {@code ""} when nothing is left — a message git would refuse as empty.
     */
    public static String strip(String text, char commentChar) {
        List<String> kept = new ArrayList<>();
        boolean blankPending = false;
        for (String line : lines(text)) {
            if (isComment(line, commentChar)) {
                continue;
            }
            String trimmed = line.stripTrailing();
            if (trimmed.isEmpty()) {
                blankPending = !kept.isEmpty();
                continue;
            }
            if (blankPending) {
                kept.add("");
                blankPending = false;
            }
            kept.add(trimmed);
        }
        return String.join("\n", kept);
    }

    /** The first line of {@code message} that says something — what a list of messages shows for it. */
    public static String subject(String message) {
        for (String line : lines(message)) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    /**
     * {@code history} with {@code message} moved (or added) to the front, without duplicates and cut to
     * {@code max} entries. A blank message leaves the list as it was.
     */
    public static List<String> remember(List<String> history, String message, int max) {
        String entry = message == null ? "" : message.strip();
        List<String> next = new ArrayList<>(history == null ? List.of() : history);
        if (entry.isEmpty()) {
            return List.copyOf(next);
        }
        next.remove(entry);
        next.add(0, entry);
        return List.copyOf(next.subList(0, Math.min(next.size(), Math.max(0, max))));
    }

    private static boolean isComment(String line, char commentChar) {
        return !line.isEmpty() && line.charAt(0) == commentChar;
    }

    private static String[] lines(String text) {
        return text == null || text.isEmpty() ? new String[0] : text.split("\\R", -1);
    }
}
