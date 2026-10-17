package com.editora.ui;

import java.util.List;

import static com.editora.i18n.Messages.tr;

/**
 * The {@code debug.toggleAdapter} and {@code debug.setAdapterPath} pickers: which debug adapters are on, and the
 * command or path each one is started with. Changing either saves the settings and re-applies debugging
 * ({@code applySupport}). Moved out of {@link DebugCoordinator}, which opens them.
 */
final class DebugAdapterPickers {

    /** Debug adapters whose enable can be toggled ("java" has no enable — gated by the Java LSP server). */
    private static final String[] DEBUG_TOGGLEABLE_ADAPTERS = {"python", "javascript"};

    /** Debug adapters with a configurable command/path. */
    private static final String[] DEBUG_PATH_ADAPTERS = {"java", "python", "javascript"};

    private final CoordinatorHost host;
    private final Runnable applySupport;

    DebugAdapterPickers(CoordinatorHost host, Runnable applySupport) {
        this.host = host;
        this.applySupport = applySupport;
    }

    /** The adapter's name as the pickers show it. */
    static String label(String id) {
        return switch (id) {
            case "python" -> "Python";
            case "javascript" -> "JavaScript";
            default -> "Java";
        };
    }

    private boolean enabled(String id) {
        var s = host.settings();
        return switch (id) {
            case "python" -> s.isPythonDebugEnabled();
            case "javascript" -> s.isJsDebugEnabled();
            default -> true;
        };
    }

    private String path(String id) {
        var s = host.settings();
        return switch (id) {
            case "python" -> s.getPythonDebugCommand();
            case "javascript" -> s.getJsDebugPath();
            default -> s.getJavaDebugPluginPath();
        };
    }

    /** Picker over the python/javascript debug adapters: toggles the chosen one's enable. */
    void chooseToggle() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.debug.toggleAdapter"),
                tr("palette.setting.pick"),
                () -> List.of(DEBUG_TOGGLEABLE_ADAPTERS),
                id -> label(id) + "  —  " + tr(enabled(id) ? "common.on" : "common.off"),
                id -> "",
                id -> {
                    if (id == null) {
                        return;
                    }
                    var s = host.settings();
                    boolean next = !enabled(id);
                    if ("python".equals(id)) {
                        s.setPythonDebugEnabled(next);
                    } else {
                        s.setJsDebugEnabled(next);
                    }
                    host.requestSave();
                    applySupport.run();
                    host.syncSettingsWindow();
                    host.setStatus(tr("status.settingToggled", label(id), tr(next ? "common.on" : "common.off")));
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Picker over the java/python/javascript debug adapters, then prompts for that adapter's path. */
    void choosePath() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.debug.setAdapterPath"),
                tr("palette.setting.pick"),
                () -> List.of(DEBUG_PATH_ADAPTERS),
                DebugAdapterPickers::label,
                this::path,
                id -> {
                    if (id == null) {
                        return;
                    }
                    // Python's "path" is the interpreter that runs debugpy (and, when the project has none of
                    // its own, the program): say so rather than ask for a debugpy path it is not.
                    String label =
                            "python".equals(id) ? tr("settings.debug.pythonCommand") : tr("palette.setting.value");
                    host.promptText(label(id), label, path(id), v -> {
                        String value = v.trim();
                        var s = host.settings();
                        switch (id) {
                            case "python" -> s.setPythonDebugCommand(value);
                            case "javascript" -> s.setJsDebugPath(value);
                            default -> s.setJavaDebugPluginPath(value);
                        }
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
