package com.editora.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * The language-server root of a file, as far as it comes from the disk: the marker files up the folders, a build's
 * outermost folder, a site or Python sub-project (PERF-01, Pauta 6.2.0). A look off the FX thread fills it; the FX
 * thread answers from it. A key nothing looked at yet answers {@code null} on the FX thread ("not known yet") and
 * starts the look, whose answer comes back on the FX thread to {@code onLearned} with the file, so the asker (a
 * buffer's sync) can go on then. A kept answer older than {@link #FRESH_NANOS} is looked at again behind it, so a
 * {@code pom.xml} made since is seen at the next ask. Off the FX thread {@link #root} looks in place.
 *
 * <p>FX-thread only state; bounded.
 */
final class LspRootMemo {

    /** What the look depends on besides the disk. */
    record Key(
            @Nullable Path project,
            Path file,
            String serverId,
            @Nullable Path looseRoot) {}

    /** The look itself: the disk part of the root ({@code head} is the start of the buffer's text). */
    @FunctionalInterface
    interface Look {
        @Nullable
        Path root(Key key, String head);
    }

    static final long FRESH_NANOS = 5_000_000_000L;
    private static final int MAX = 512;

    private record Kept(Optional<Path> root, long at) {}

    private final Look look;
    private final Consumer<Path> onLearned;
    private final Map<Key, Kept> known = new HashMap<>();
    private final Set<Key> looking = new HashSet<>();

    /** The key {@link #root} is asking for while it starts its look. */
    private @Nullable Key asking;

    LspRootMemo(Look look, Consumer<Path> onLearned) {
        this.look = look;
        this.onLearned = onLearned;
    }

    /** Whether {@code key} has an answer kept. */
    boolean knows(Key key) {
        return known.containsKey(key);
    }

    /**
     * The root for {@code key}: off the FX thread looked at now; on it the kept answer, or null while the first look
     * runs (see the class comment).
     */
    @Nullable
    Path root(Key key, String head) {
        if (!onFx()) {
            return look.root(key, head);
        }
        Kept kept = known.get(key);
        if (kept == null) {
            asking = key;
            try {
                start(key, head, true);
            } finally {
                asking = null;
            }
            Kept now = known.get(key); // there when the look ran in place (a test's OffFx runner)
            return now == null ? null : now.root().orElse(null);
        }
        if (System.nanoTime() - kept.at() > FRESH_NANOS) {
            start(key, head, false);
        }
        return kept.root().orElse(null);
    }

    /** Drops every kept answer (the project root moved, say). */
    void forget() {
        known.clear();
    }

    private void start(Key key, String head, boolean first) {
        if (!looking.add(key)) {
            return;
        }
        com.editora.perf.OffFx.then("lsp-root-look", () -> Optional.ofNullable(look.root(key, head)), answer -> {
            looking.remove(key);
            if (!answer.ok() && !first) {
                return; // a failed refresh keeps what was known
            }
            // A failed first look answers "no root", as the look on the FX thread did when it threw: the asker
            // goes on rather than waiting for ever.
            Optional<Path> root = answer.or(Optional.empty());
            if (known.size() >= MAX) {
                known.clear();
            }
            Kept before = known.put(key, new Kept(root, System.nanoTime()));
            if (key.equals(asking)) {
                return; // answered within the ask itself: the asker has it
            }
            if (first || before == null || !before.root().equals(root)) {
                onLearned.accept(key.file());
            }
        });
    }

    private static boolean onFx() {
        try {
            return javafx.application.Platform.isFxApplicationThread();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
