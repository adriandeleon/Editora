package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sessions one compound started — a launch.json compound or a {@link com.editora.config.RunCompound}. With
 * {@code stopAll}, stopping one of them stops the others. A session belongs to it for the launch it joined with: once
 * that session is started again for something else, it no longer does. FX thread only.
 */
final class DebugCompound {

    final String name;
    final boolean stopAll;

    /** Each session's {@link DebugSession#runtimeGeneration} when it joined. */
    private final Map<DebugSession, Long> joined = new LinkedHashMap<>();

    /** Set while Stop goes round the sessions, so each one's Stop does not go round again. */
    boolean stopping;

    DebugCompound(String name, boolean stopAll) {
        this.name = name;
        this.stopAll = stopAll;
    }

    /** {@code session} is about to launch one of this compound's configurations. */
    void join(DebugSession session) {
        joined.put(session, session.runtimeGeneration);
        session.compound = this;
    }

    /**
     * Whether {@code session} still runs the launch it joined with. Its generation moves at each start and end: by
     * one or two to its start (an end reported first), by one more to its end — a later launch is past that.
     */
    boolean holds(DebugSession session) {
        Long at = joined.get(session);
        return at != null && session.compound == this && session.runtimeGeneration <= at + 2;
    }

    /** The sessions that still run this compound's launches, in the order they joined. */
    List<DebugSession> sessions() {
        List<DebugSession> out = new ArrayList<>();
        for (DebugSession s : joined.keySet()) {
            if (holds(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
