package com.editora.ui;

import static com.editora.i18n.Messages.tr;

/**
 * The {@code debug.toggleAdapter} and {@code debug.setAdapterPath} pickers: which debug adapters are on, and the
 * command or path each one is started with. Changing either saves the settings and re-applies debugging
 * ({@code applySupport}). Moved out of {@link DebugCoordinator}, which opens them.
 */
final class DebugAdapterPickers {

    private final CoordinatorHost host;
    private final Runnable applySupport;

    DebugAdapterPickers(CoordinatorHost host, Runnable applySupport) {
        this.host = host;
        this.applySupport = applySupport;
    }

    /** The adapter's name as the pickers show it. */
    static String label(String id) {
        DebugAdapterSettings.Binding b = DebugAdapterSettings.of(id);
        return b == null ? id : b.language().displayName();
    }

    private boolean enabled(String id) {
        DebugAdapterSettings.Binding b = DebugAdapterSettings.of(id);
        return b == null || b.isEnabled(host.settings());
    }

    private String path(String id) {
        DebugAdapterSettings.Binding b = DebugAdapterSettings.of(id);
        return b == null ? "" : b.path().apply(host.settings());
    }

    /** Picker over the debug adapters with a switch of their own (Python, JavaScript): toggles the chosen one. */
    void chooseToggle() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.debug.toggleAdapter"),
                tr("palette.setting.pick"),
                () -> DebugAdapterSettings.toggleable().stream()
                        .map(DebugAdapterSettings.Binding::id)
                        .toList(),
                id -> label(id) + "  —  " + tr(enabled(id) ? "common.on" : "common.off"),
                id -> "",
                id -> {
                    if (id == null) {
                        return;
                    }
                    DebugAdapterSettings.Binding b = DebugAdapterSettings.of(id);
                    if (b == null || b.setEnabled() == null) {
                        return;
                    }
                    boolean next = !enabled(id);
                    b.setEnabled().accept(host.settings(), next);
                    host.requestSave();
                    applySupport.run();
                    host.syncSettingsWindow();
                    host.setStatus(tr("status.settingToggled", label(id), tr(next ? "common.on" : "common.off")));
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Picker over the debug adapters with a command or path of their own, then prompts for it. */
    void choosePath() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.debug.setAdapterPath"),
                tr("palette.setting.pick"),
                () -> DebugAdapterSettings.all().stream()
                        .map(DebugAdapterSettings.Binding::id)
                        .toList(),
                DebugAdapterPickers::label,
                this::path,
                id -> {
                    if (id == null) {
                        return;
                    }
                    DebugAdapterSettings.Binding b = DebugAdapterSettings.of(id);
                    if (b == null) {
                        return;
                    }
                    host.promptText(label(id), tr(b.pathLabelKey()), path(id), v -> {
                        String value = v.trim();
                        b.setPath().accept(host.settings(), value);
                        host.requestSave();
                        applySupport.run();
                        host.syncSettingsWindow();
                        host.setStatus(tr("status.settingChanged", label(id), value));
                    });
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }
}
