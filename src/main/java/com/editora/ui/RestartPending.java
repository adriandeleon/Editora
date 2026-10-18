package com.editora.ui;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.editora.config.Settings;

/**
 * Settings that apply only when Editora starts, and how many of them differ from what this run started with —
 * the count the Settings footer turns into "Restart Editora to apply N changes" with a Restart now button.
 * {@link #captureStart} runs once per process, when the first window manager is made (before any change).
 */
final class RestartPending {

    /** Each restart-only setting, by name, and how to read it. */
    static final Map<String, Function<Settings, Object>> SETTINGS = Map.of(
            "uiLanguage", Settings::getUiLanguage,
            "extendedWindow", Settings::isExtendedWindow,
            "pluginSupport", Settings::isPluginSupport);

    private static final Map<String, Object> AT_START = new ConcurrentHashMap<>();

    private RestartPending() {}

    /** Records the restart-only values this run uses; later calls keep the first record. */
    static synchronized void captureStart(Settings settings) {
        if (AT_START.isEmpty()) {
            SETTINGS.forEach((name, get) -> AT_START.put(name, String.valueOf(get.apply(settings))));
        }
    }

    /** The restart-only settings that differ from this run's start (none before {@link #captureStart}). */
    static List<String> changed(Settings settings) {
        return SETTINGS.entrySet().stream()
                .filter(e -> AT_START.containsKey(e.getKey())
                        && !Objects.equals(
                                AT_START.get(e.getKey()),
                                String.valueOf(e.getValue().apply(settings))))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Forgets the record (tests that start a new "run" in the same JVM). */
    static synchronized void forgetStart() {
        AT_START.clear();
    }
}
