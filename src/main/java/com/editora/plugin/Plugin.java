package com.editora.plugin;

/**
 * The entry point a Java plugin implements. The plugin jar declares its implementing class in
 * {@code plugin.json} ({@code "main"}); the manager instantiates it with the no-arg constructor and calls
 * {@link #start(PluginContext)} once per open window.
 *
 * <p>Plugins run with <b>full trust</b> (no sandbox) — they have the same access as the app. They are
 * opt-in: nothing loads unless the user enables the master "Enable plugins" setting and the individual
 * plugin.
 *
 * <p><b>Threads.</b> {@link #start} and {@link #stop} run on the JavaFX application thread while a window is
 * being built or closed, so they must return quickly: do I/O, network calls and long setup on a thread of your
 * own (make it a daemon thread). A slow {@code start} is named to the user; one that never returns would hang
 * Editora at every launch (the user's way out is {@code --disable-plugins}).
 */
public interface Plugin {

    /**
     * Called once per window after the UI is built; register commands/keys/tool windows/hooks here. If it
     * throws, what it registered is taken back, {@link #stop} is called, and the user is told.
     */
    void start(PluginContext context);

    /**
     * Called when a window the plugin was started in closes (and right after a {@link #start} that threw).
     * Stop timers and threads this window's {@code start} began. Default: no-op.
     */
    default void stop() {}
}
