package com.editora.ui;

import java.util.function.BooleanSupplier;

import com.editora.lsp.FormatFailure;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What Format Document says when it applies nothing: that the request failed and how ({@link FormatFailure}: it
 * timed out, the server went away, or it refused), else the no-change words — for the Bash server, why shfmt changed
 * nothing ({@link BashLspUi#formatNoChange}); bash-language-server answers "no edits" when shfmt is missing or cannot
 * parse the script, too. A failed format used to say "Already formatted" (HTML review A5).
 */
final class FormatOutcomes {

    private FormatOutcomes() {}

    static String noChange(
            @Nullable FormatFailure failure,
            @Nullable String serverId,
            BooleanSupplier shfmtFound,
            @Nullable String loggedError) {
        if (failure == null) {
            return BashLspUi.formatNoChange(serverId, shfmtFound, loggedError);
        }
        return switch (failure.kind()) {
            case NO_ANSWER -> tr("status.lsp.formatFailedNoAnswer");
            case LOST -> tr("status.lsp.formatFailedLost");
            case REFUSED -> tr("status.lsp.formatFailedRefused", failure.detail() == null ? "" : failure.detail());
        };
    }
}
