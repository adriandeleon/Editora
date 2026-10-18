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
            case CODE_NOT_LOADED -> tr("settings.plugins.error.codeNotLoaded");
        };
    }

    /** The underlying (English) message, for a tooltip; null when there is none. */
    static String detail(PluginDescriptor d) {
        PluginDescriptor.LoadProblem p = d.problem();
        return p == null || p.detail().isBlank() ? null : p.detail();
    }
}
