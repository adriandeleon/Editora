package com.editora.ui;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.editora.config.Settings;
import com.editora.dap.DapServerRegistry;
import com.editora.dap.DebugLanguage;
import com.editora.dap.DebugLanguages;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The settings of each debug adapter that has its own: whether it is on, the command or path it is started with,
 * what installs it, and what to say when it is off or missing. The Debugging page, the adapter pickers and the
 * coordinator's "can this file be debugged" read them here rather than each comparing language names. Editora's own
 * JVM adapter and Metals' have no settings of their own (only the debugging switch).
 */
final class DebugAdapterSettings {

    private DebugAdapterSettings() {}

    /**
     * One adapter's settings.
     *
     * @param language the adapter language's profile
     * @param enabled whether it is on, or null when it has no switch of its own (Java's is the Java server's)
     * @param setEnabled turns it on or off; null with {@code enabled}
     * @param path the command or path it is started with ("" = found by itself)
     * @param setPath sets that
     * @param pathLabelKey the picker's prompt for that path
     * @param toolId the tool the Debugging page's status row checks
     * @param installId what Install fetches for it
     * @param offKey what is said when it is switched off; null with {@code enabled}
     * @param missing what is said when it is on but not found, with the settings and the project's paths
     */
    record Binding(
            DebugLanguage language,
            @Nullable Function<Settings, Boolean> enabled,
            @Nullable BiConsumer<Settings, Boolean> setEnabled,
            Function<Settings, String> path,
            BiConsumer<Settings, String> setPath,
            String pathLabelKey,
            String toolId,
            String installId,
            @Nullable String offKey,
            @Nullable BiFunction<Settings, ProjectScope, String> missing) {

        String id() {
            return language.id();
        }

        /** Whether it has an on/off switch of its own. */
        boolean toggleable() {
            return enabled != null;
        }

        /** Whether it is on: always, without a switch of its own. */
        boolean isEnabled(Settings s) {
            return enabled == null || enabled.apply(s);
        }
    }

    /** Python's: the interpreter that runs debugpy (and, when the project has none, the program). */
    private static String debugpyMissing(Settings s, ProjectScope scope) {
        String command = scope.debugTool("python", s.getPythonDebugCommand());
        return tr(
                "status.debug.debugpyMissing",
                command.isBlank() ? DapServerRegistry.DEFAULT_PYTHON_INTERPRETER : command);
    }

    private static final List<Binding> ALL = List.of(
            new Binding(
                    DebugLanguages.JAVA,
                    null,
                    null,
                    Settings::getJavaDebugPluginPath,
                    Settings::setJavaDebugPluginPath,
                    "palette.setting.value",
                    "java-debug",
                    "java",
                    null,
                    null),
            new Binding(
                    DebugLanguages.PYTHON,
                    Settings::isPythonDebugEnabled,
                    Settings::setPythonDebugEnabled,
                    Settings::getPythonDebugCommand,
                    Settings::setPythonDebugCommand,
                    // the interpreter that runs debugpy: say so rather than ask for a debugpy path it is not
                    "settings.debug.pythonCommand",
                    "debugpy",
                    "python",
                    "status.debug.pythonOff",
                    DebugAdapterSettings::debugpyMissing),
            new Binding(
                    DebugLanguages.JAVASCRIPT,
                    Settings::isJsDebugEnabled,
                    Settings::setJsDebugEnabled,
                    Settings::getJsDebugPath,
                    Settings::setJsDebugPath,
                    "palette.setting.value",
                    "js-debug",
                    "js-debug",
                    "status.debug.jsOff",
                    (s, scope) -> tr("status.debug.jsDebugMissing")));

    /** Every adapter with settings of its own, in the order the pickers list them. */
    static List<Binding> all() {
        return ALL;
    }

    /** The settings of adapter language {@code id}, or null when it has none of its own. */
    static @Nullable Binding of(@Nullable String id) {
        for (Binding b : ALL) {
            if (b.id().equals(id)) {
                return b;
            }
        }
        return null;
    }

    /** Those with an on/off switch of their own. */
    static List<Binding> toggleable() {
        return ALL.stream().filter(Binding::toggleable).toList();
    }
}
