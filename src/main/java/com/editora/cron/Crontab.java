package com.editora.cron;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits crontab text into its meaningful lines for the preview: environment assignments
 * ({@code NAME=value}), job lines (a schedule plus a command), and the parse errors on malformed job
 * lines. Comments and blank lines are dropped. Pure, java.base-only, unit-tested.
 *
 * <p>A job line is either a 5-field schedule followed by a command, or an {@code @macro} followed by a
 * command. The schedule is handed to {@link CronExpression#parse} so each job carries its decoded
 * expression (or the field-level error).
 */
public final class Crontab {

    /** A {@code NAME=value} line (e.g. {@code MAILTO=ops@acme.com}). */
    public record Assignment(String name, String value, int line) {}

    /** One scheduled job: its raw schedule, decoded expression (null on error), command, and 1-based line. */
    public record Job(String rawSchedule, CronExpression expr, String error, String command, int line) {
        public boolean ok() {
            return error == null;
        }
    }

    private final List<Assignment> assignments;
    private final List<Job> jobs;

    private Crontab(List<Assignment> assignments, List<Job> jobs) {
        this.assignments = assignments;
        this.jobs = jobs;
    }

    public List<Assignment> assignments() {
        return assignments;
    }

    public List<Job> jobs() {
        return jobs;
    }

    public static Crontab parse(String text) {
        List<Assignment> assignments = new ArrayList<>();
        List<Job> jobs = new ArrayList<>();
        if (text == null) {
            return new Crontab(assignments, jobs);
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Assignment a = assignment(line, lineNo);
            if (a != null) {
                assignments.add(a);
                continue;
            }
            jobs.add(job(line, lineNo));
        }
        return new Crontab(assignments, jobs);
    }

    /** A {@code NAME=value} assignment, or {@code null} when the line is a job. Cron env names precede any space. */
    private static Assignment assignment(String line, int lineNo) {
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String name = line.substring(0, eq).trim();
        // An assignment name is a single token (no whitespace) — otherwise it's a schedule, not NAME=value.
        if (name.isEmpty() || name.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        String value = unquote(line.substring(eq + 1).trim());
        return new Assignment(name, value, lineNo);
    }

    private static Job job(String line, int lineNo) {
        String schedule;
        String command;
        if (line.startsWith("@")) {
            int sp = firstWhitespace(line);
            schedule = sp < 0 ? line : line.substring(0, sp);
            command = sp < 0 ? "" : line.substring(sp).strip();
        } else {
            int[] span = fifthFieldEnd(line);
            if (span == null) {
                return new Job(line, null, "expected 5 schedule fields", "", lineNo);
            }
            schedule = line.substring(0, span[0]);
            command = line.substring(span[1]).strip();
        }
        CronExpression.Parsed p = CronExpression.parse(schedule);
        return new Job(schedule.strip(), p.expr(), p.error(), command, lineNo);
    }

    /** {@code {end,commandStart}} offsets after the 5th whitespace-separated field, or null if there aren't 5. */
    private static int[] fifthFieldEnd(String line) {
        int i = 0;
        int n = line.length();
        int fieldEnd = -1;
        for (int field = 0; field < 5; field++) {
            while (i < n && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i >= n) {
                return null;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            fieldEnd = i;
            if (field < 4 && i >= n) {
                return null; // ran out before the 5th field
            }
        }
        int cmdStart = i;
        while (cmdStart < n && Character.isWhitespace(line.charAt(cmdStart))) {
            cmdStart++;
        }
        return new int[] {fieldEnd, cmdStart};
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String s) {
        if (s.length() >= 2
                && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
                        || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
