package com.editora.ui;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What a Settings → Plugins row says about a plugin compared with what this session runs (the launch set):
 * the change a restart will make, if any. Pure; the page binds nothing per row to a per-window snapshot any
 * more — the "Restart to apply" pill used to compare against the state the row had when that window's Settings
 * first listed it, so a plugin installed and accepted in this session (or an update) showed no pill at all.
 */
final class PluginRowState {

    private PluginRowState() {}

    enum Pill {
        /** Nothing will change at a restart. */
        NONE,
        /** Enabled, but not running this session: a restart loads it. */
        RESTART_TO_LOAD,
        /** Switched off, but running this session: a restart unloads it. */
        RESTART_TO_UNLOAD,
        /** A new version is in place on disk, or waits for the restart: a restart runs it. */
        UPDATE_PENDING,
        /** Removed while it ran: a restart deletes it. */
        REMOVAL_PENDING,
        /** Switched on, but what it can do changed since the user agreed: not loaded until enabled again. */
        CHANGED
    }

    /**
     * @param allowed        the store allows it as it is on disk now (enabled, same capabilities)
     * @param switchedOn     the store has it switched on (whatever its capabilities)
     * @param running        it runs in this session
     * @param runningVersion the version that runs (when running)
     * @param diskVersion    the version on disk
     * @param pendingUpdate  the version of an update waiting for the restart, or null
     * @param pendingRemoval it is marked for removal at the restart
     */
    static Pill pill(
            boolean allowed,
            boolean switchedOn,
            boolean running,
            @Nullable String runningVersion,
            String diskVersion,
            @Nullable String pendingUpdate,
            boolean pendingRemoval) {
        if (pendingRemoval) {
            return Pill.REMOVAL_PENDING;
        }
        if (pendingUpdate != null && switchedOn) {
            return Pill.UPDATE_PENDING;
        }
        if (switchedOn && !allowed) {
            return Pill.CHANGED;
        }
        if (allowed && !running) {
            return Pill.RESTART_TO_LOAD;
        }
        if (!allowed && running) {
            return Pill.RESTART_TO_UNLOAD;
        }
        if (running && !Objects.equals(blank(runningVersion), blank(diskVersion))) {
            return Pill.UPDATE_PENDING;
        }
        return Pill.NONE;
    }

    private static String blank(@Nullable String s) {
        return s == null ? "" : s.strip();
    }

    /** The pill's text, or null for {@link Pill#NONE}. */
    static @Nullable String text(Pill pill, @Nullable String pendingVersion) {
        return switch (pill) {
            case NONE -> null;
            case RESTART_TO_LOAD, RESTART_TO_UNLOAD -> tr("settings.plugins.restartPill");
            case UPDATE_PENDING ->
                pendingVersion == null || pendingVersion.isBlank()
                        ? tr("settings.plugins.restartPill")
                        : tr("settings.plugins.updatePill", PluginDisclosure.clean(pendingVersion, 40));
            case REMOVAL_PENDING -> tr("settings.plugins.removalPill");
            case CHANGED -> tr("settings.plugins.changedPill");
        };
    }
}
