package com.editora.ui;

import java.util.List;

import com.editora.plugin.PluginProblem;

import static com.editora.i18n.Messages.tr;

/** Says why a registry fetch, a download or an install failed, in the user's language. */
final class PluginProblems {

    private PluginProblems() {}

    /** The sentence for {@code p}. */
    static String text(PluginProblem p) {
        if (p == null) {
            return "";
        }
        List<String> a = p.args();
        String first = a.isEmpty() ? "" : PluginDisclosure.clean(a.getFirst(), 120);
        String second = a.size() < 2 ? "" : PluginDisclosure.clean(a.get(1), 120);
        return switch (p.kind()) {
            case NOT_HTTPS -> tr("plugins.problem.notHttps");
            case NO_CHECKSUM -> tr("plugins.problem.noChecksum");
            case HTTP_STATUS -> tr("plugins.problem.httpStatus", first);
            case UNREACHABLE -> tr("plugins.problem.unreachable", first);
            case TIMED_OUT -> tr("plugins.problem.timedOut", first);
            case CHECKSUM_MISMATCH -> tr("plugins.problem.checksum");
            case TOO_LARGE -> tr("plugins.problem.tooLarge", first);
            case ARCHIVE_INVALID -> tr("plugins.problem.archiveInvalid");
            case NO_MANIFEST -> tr("plugins.problem.noManifest");
            case MANIFEST_INVALID ->
                first.isEmpty()
                        ? tr("settings.plugins.error.manifestInvalid")
                        : tr("settings.plugins.error.manifestInvalidAt", first, second);
            case INVALID_ID -> tr("plugins.problem.invalidId", first);
            case RESERVED_ID -> tr("plugins.problem.reservedId", first);
            case RETIRED -> tr("plugins.problem.retired", first);
            case IN_THE_WAY -> tr("plugins.problem.inTheWay", first);
            case SYMLINKED -> tr("plugins.problem.symlinked", first);
            case ID_MISMATCH -> tr("plugins.problem.idMismatch", first, second);
            case VERSION_MISMATCH -> tr("plugins.problem.versionMismatch", first, second);
            case INDEX_INVALID -> tr("plugins.problem.indexInvalid");
            case INDEX_TOO_NEW -> tr("plugins.problem.indexTooNew", first);
            case FAILED ->
                p.detail().isBlank() ? tr("plugins.problem.failed") : PluginDisclosure.clean(p.detail(), 300);
        };
    }
}
