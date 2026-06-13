package com.editora.plugin;

/**
 * The entry point a Java plugin implements. The plugin jar declares its implementing class in
 * {@code plugin.json} ({@code "main"}); the manager instantiates it with the no-arg constructor and calls
 * {@link #start(PluginContext)} once per open window.
 *
 * <p>Plugins run with <b>full trust</b> (no sandbox) — they have the same access as the app. They are
 * opt-in: nothing loads unless the user enables the master "Enable plugins" setting and the individual
 * plugin.
 */
public interface Plugin {

    /** Called once per window after the UI is built; register commands/keys/tool windows/hooks here. */
    void start(PluginContext context);

    /** Called when a window the plugin was started in closes. Default: no-op. */
    default void stop() {
    }
}
