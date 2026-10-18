package com.editora.ui;

import com.editora.plugin.PluginDescriptor;

import static com.editora.i18n.Messages.tr;

/** Says why a plugin failed to load, in the user's language (Settings → Plugins → Installed Plugins). */
final class PluginLoadProblems {

    private PluginLoadProblems() {}

    /** The sentence for {@code d}'s load failure, or null when it loaded. */
    static String text(PluginDescriptor d) {
        PluginDescriptor.LoadProblem p = d.problem();
        if (p == null) {
            return d.loadError(); // a failure recorded without a kind: as it was written
        }
        return text(p);
    }

    /** The sentence for problem {@code p} (a load failure, or a start failure recorded this session). */
    static String text(PluginDescriptor.LoadProblem p) {
        return switch (p.kind()) {
            case MANIFEST_INVALID ->
                p.args().getFirst().isEmpty()
                        ? tr("settings.plugins.error.manifestInvalid")
                        : tr(
                                "settings.plugins.error.manifestInvalidAt",
                                p.args().get(0),
                                p.args().get(1));
            case MANIFEST_UNREADABLE -> tr("settings.plugins.error.manifestUnreadable");
            case ID_NOT_ALLOWED ->
                tr("settings.plugins.error.idNotAllowed", p.args().getFirst());
            case ID_MISMATCH ->
                tr(
                        "settings.plugins.error.idMismatch",
                        p.args().get(0),
                        p.args().get(1));
            case CODE_NOT_LOADED -> tr("settings.plugins.error.codeNotLoaded");
            case MAIN_NOT_FOUND ->
                tr("settings.plugins.error.mainNotFound", p.args().getFirst());
            case MAIN_NOT_A_PLUGIN ->
                tr("settings.plugins.error.mainNotAPlugin", p.args().getFirst());
            case START_FAILED -> tr("settings.plugins.error.startFailed");
            case SLOW_START -> tr("settings.plugins.error.slowStart", p.args().getFirst());
        };
    }

    /** The underlying (English) message, for a tooltip; null when there is none. */
    static String detail(PluginDescriptor d) {
        return detail(d.problem());
    }

    /** The underlying message of {@code p}, for a tooltip; null when there is none. */
    static String detail(PluginDescriptor.LoadProblem p) {
        return p == null || p.detail().isBlank() ? null : p.detail();
    }
}
