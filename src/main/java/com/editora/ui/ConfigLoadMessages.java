package com.editora.ui;

import java.util.List;

import com.editora.config.migration.ConfigLoadProblem;

import static com.editora.i18n.Messages.tr;

/** Words a {@link ConfigLoadProblem} for the status bar and message log. */
final class ConfigLoadMessages {

    /** How many reset value names are listed before the rest are elided. */
    static final int MAX_NAMES = 8;

    private ConfigLoadMessages() {}

    /**
     * The message for {@code problem}: what could not be read, then either where the original content was
     * kept or — when it could not be kept and {@code writeProtected} — that the file is left untouched.
     */
    static String describe(ConfigLoadProblem problem, boolean writeProtected) {
        String name = problem.file().getFileName().toString();
        String what =
                switch (problem.kind()) {
                    case VALUES_SKIPPED ->
                        tr("status.config.valuesReset", problem.skipped().size(), name, names(problem.skipped()));
                    case UNREADABLE -> tr("status.config.unreadable", name);
                    case NEWER_VERSION -> tr("status.config.newerVersion", name);
                    case NOT_UTF8 -> tr("status.config.notUtf8", name);
                };
        if (problem.backup() != null) {
            return tr(
                    "status.config.backupAt",
                    what,
                    problem.backup().getFileName().toString());
        }
        return writeProtected ? tr("status.config.notSaved", what, name) : what;
    }

    private static String names(List<String> skipped) {
        if (skipped.size() <= MAX_NAMES) {
            return String.join(", ", skipped);
        }
        return String.join(", ", skipped.subList(0, MAX_NAMES)) + ", …";
    }
}
