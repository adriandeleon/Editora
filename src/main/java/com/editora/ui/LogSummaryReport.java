package com.editora.ui;

import java.util.List;
import java.util.Locale;

import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogSummary;
import com.editora.logviewer.LogTimes;

import static com.editora.i18n.Messages.tr;

/** A {@link LogSummary} as the Markdown report Log: Summarize opens: tables, and bars for the histogram. */
final class LogSummaryReport {

    private LogSummaryReport() {}

    /** Widest histogram bar, in block characters. */
    static final int BAR = 30;

    static String markdown(LogSummary.Summary s, String name, LogTimes.Context context) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(tr("log.summary.heading", cell(name))).append("\n\n");
        md.append("| | |\n|---|---|\n");
        row(md, tr("log.summary.lines"), count(s.lines()));
        row(md, tr("log.summary.records"), count(s.records()));
        if (s.first() != LogTimes.NONE) {
            row(md, tr("log.summary.from"), LogTimes.format(s.first(), context));
            row(md, tr("log.summary.to"), LogTimes.format(s.last(), context));
            row(md, tr("log.summary.span"), duration(s.last() - s.first()));
        }
        md.append('\n');

        md.append("## ").append(tr("log.summary.levels")).append("\n\n");
        md.append("| ")
                .append(tr("log.summary.level"))
                .append(" | ")
                .append(tr("log.summary.records"))
                .append(" |\n");
        md.append("|---|---:|\n");
        for (int r = LogLevel.FATAL.rank(); r >= 0; r--) {
            LogLevel level = LogLevel.values()[r];
            Integer n = s.levels().get(level);
            if (n != null) {
                row(md, level.name(), count(n));
            }
        }
        if (s.unleveled() > 0) {
            row(md, tr("log.summary.noLevel"), count(s.unleveled()));
        }
        md.append('\n');

        if (!s.buckets().isEmpty()) {
            md.append("## ")
                    .append(tr("log.summary.overTime", duration(s.bucketMillis())))
                    .append("\n\n");
            md.append("| ")
                    .append(tr("log.summary.from"))
                    .append(" | ")
                    .append(tr("log.summary.records"))
                    .append(" | ")
                    .append(tr("log.summary.problems"))
                    .append(" | |\n");
            md.append("|---|---:|---:|---|\n");
            int max = s.buckets().stream()
                    .mapToInt(LogSummary.Bucket::records)
                    .max()
                    .orElse(1);
            for (LogSummary.Bucket b : s.buckets()) {
                md.append("| ")
                        .append(LogTimes.format(b.start(), context))
                        .append(" | ")
                        .append(count(b.records()))
                        .append(" | ")
                        .append(b.problems() == 0 ? "" : count(b.problems()))
                        .append(" | ")
                        .append(bar(b.records(), max))
                        .append(" |\n");
            }
            md.append('\n');
        }

        md.append("## ").append(tr("log.summary.messages")).append("\n\n");
        List<LogSummary.Message> messages = s.messages();
        if (messages.isEmpty()) {
            md.append(tr("log.summary.noRepeats")).append("\n\n");
        } else {
            md.append("| ")
                    .append(tr("log.summary.count"))
                    .append(" | ")
                    .append(tr("log.summary.level"))
                    .append(" | ")
                    .append(tr("log.summary.firstLine"))
                    .append(" | ")
                    .append(tr("log.summary.message"))
                    .append(" |\n");
            md.append("|---:|---|---:|---|\n");
            for (LogSummary.Message m : messages) {
                md.append("| ")
                        .append(count(m.count()))
                        .append(" | ")
                        .append(m.level() == null ? "" : m.level().name())
                        .append(" | ")
                        .append(m.firstLine() + 1)
                        .append(" | ")
                        .append(code(m.template()))
                        .append(" |\n");
            }
            md.append('\n');
        }

        if (!s.gaps().isEmpty()) {
            md.append("## ").append(tr("log.summary.gaps")).append("\n\n");
            md.append("| ")
                    .append(tr("log.summary.silence"))
                    .append(" | ")
                    .append(tr("log.summary.from"))
                    .append(" | ")
                    .append(tr("log.summary.to"))
                    .append(" | ")
                    .append(tr("log.summary.lineRange"))
                    .append(" |\n");
            md.append("|---:|---|---|---|\n");
            for (LogSummary.Gap g : s.gaps()) {
                md.append("| ")
                        .append(duration(g.millis()))
                        .append(" | ")
                        .append(LogTimes.format(g.from(), context))
                        .append(" | ")
                        .append(LogTimes.format(g.to(), context))
                        .append(" | ")
                        .append(g.fromLine() + 1)
                        .append("–")
                        .append(g.toLine() + 1)
                        .append(" |\n");
            }
            md.append('\n');
        }
        return md.toString();
    }

    private static void row(StringBuilder md, String label, String value) {
        md.append("| ").append(cell(label)).append(" | ").append(value).append(" |\n");
    }

    /** Text safe in a table cell: its pipes escaped, on one line. */
    static String cell(String text) {
        return text.replace("\\", "\\\\").replace("|", "\\|").replace('\n', ' ');
    }

    /** {@code text} as code in a table cell: backticks can't be escaped there, so they become quotes. */
    static String code(String text) {
        return "`" + cell(text.replace('`', '\'')) + "`";
    }

    private static String count(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** A bar of {@code n} against {@code max}: at least one block for any n > 0. */
    static String bar(int n, int max) {
        if (n <= 0 || max <= 0) {
            return "";
        }
        return "█".repeat(Math.max(1, (int) Math.round((double) n * BAR / max)));
    }

    /** {@code millis} read at a glance: {@code 350 ms}, {@code 42 s}, {@code 5 min}, {@code 3 h 12 min}, {@code 2 d 4 h}. */
    static String duration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        long s = millis / 1000;
        if (s < 60) {
            return s + " s";
        }
        long m = s / 60;
        if (m < 60) {
            return s % 60 == 0 ? m + " min" : m + " min " + s % 60 + " s";
        }
        long h = m / 60;
        if (h < 24) {
            return m % 60 == 0 ? h + " h" : h + " h " + m % 60 + " min";
        }
        long d = h / 24;
        return h % 24 == 0 ? d + " d" : d + " d " + h % 24 + " h";
    }
}
