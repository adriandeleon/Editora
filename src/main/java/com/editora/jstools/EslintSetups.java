package com.editora.jstools;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * The ESLint setup ({@link JsTools#eslint}) last found for each folder, so the check after a pause in typing does not
 * walk the folders for the configuration and the installed ESLint again every time (perf review 2026-11-23 L3). A
 * check that may follow a change on disk — a tab shown, a save, a settings or trust change, Fix All — looks again and
 * keeps what it finds; a typing pause takes an answer at most {@link #FRESH_NANOS} old. Blocking: off the FX thread.
 */
public final class EslintSetups {

    /** How old an answer a typing pause takes without looking again. */
    public static final long FRESH_NANOS = 30_000_000_000L;

    /** The most folders kept; past it the memo starts over. */
    static final int MAX = 256;

    private record Key(Path folder, @Nullable Path project) {}

    private record Kept(JsTools.EslintSetup setup, long at) {}

    private final Map<Key, Kept> kept = new ConcurrentHashMap<>();
    private final BiFunction<Path, @Nullable Path, JsTools.EslintSetup> look;
    private final LongSupplier clock;

    /** On this machine's file system. */
    public EslintSetups() {
        this((file, project) -> JsTools.eslint(file, JsTools.Lookup.local(project)), System::nanoTime);
    }

    EslintSetups(BiFunction<Path, @Nullable Path, JsTools.EslintSetup> look, LongSupplier clock) {
        this.look = look;
        this.clock = clock;
    }

    /**
     * {@code file}'s setup in {@code project}: when {@code reuse}, a recent answer for its folder if there is one;
     * otherwise (and when there is none) looked up now and kept. The lookup depends on the file's folder only.
     */
    public JsTools.EslintSetup get(Path file, @Nullable Path project, boolean reuse) {
        Path folder = file.toAbsolutePath().normalize().getParent();
        if (folder == null) {
            return look.apply(file, project);
        }
        Key key = new Key(folder, project);
        long now = clock.getAsLong();
        if (reuse) {
            Kept known = kept.get(key);
            if (known != null && now - known.at() < FRESH_NANOS) {
                return known.setup();
            }
        }
        JsTools.EslintSetup setup = look.apply(file, project);
        if (kept.size() >= MAX) {
            kept.clear();
        }
        kept.put(key, new Kept(setup, now));
        return setup;
    }
}
