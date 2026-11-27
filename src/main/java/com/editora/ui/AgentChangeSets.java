package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * What agents changed, turn by turn: before an agent's first write to a file in a turn, the file's text then (null
 * for a file the write created). An AI Agent turn is one prompt and its answer; MCP edits have no turns, so they
 * are grouped until {@link #MCP_QUIET_MILLIS} pass without one. The newest {@link #KEPT} turns are kept, in memory
 * only. Thread-safe: an ACP write to a closed file is recorded on the agent's thread.
 */
final class AgentChangeSets {

    /** How long without an MCP edit ends the MCP agent's current turn. */
    static final long MCP_QUIET_MILLIS = 120_000;

    /** How many turns are kept. */
    static final int KEPT = 20;

    /** The file texts before turn {@code number} of {@code agent} first wrote them, in the order written. */
    record Turn(int number, String agent, long startedAt, Map<Path, @Nullable String> before) {
        int fileCount() {
            return before.size();
        }
    }

    /** Hears a turn's first write to a file whose text before it is known: the turn, the file, that text. */
    interface FirstWrite {
        void before(Turn turn, Path file, String text);
    }

    private volatile @Nullable FirstWrite onFirstWrite;

    /** Who hears each turn's first write to an open file (the window, for a Local History label). */
    void setOnFirstWrite(@Nullable FirstWrite listener) {
        this.onFirstWrite = listener;
    }

    private final LongSupplier clock;
    private final Deque<Turn> turns = new ArrayDeque<>();
    private int next = 1;
    private @Nullable Turn acp;
    private @Nullable Turn mcp;
    private long lastMcpEdit;

    AgentChangeSets(LongSupplier clock) {
        this.clock = clock;
    }

    /** An AI Agent turn begins (a prompt is sent). */
    synchronized void beginTurn(String agent) {
        acp = start(agent);
    }

    /** The AI Agent turn ended; the files it changed, or none. */
    synchronized @Nullable Turn endTurn() {
        Turn done = acp;
        acp = null;
        return done == null || done.before().isEmpty() ? null : done;
    }

    /**
     * Before an AI Agent write to {@code file}: its text then (null: it did not exist). {@code label}: tell
     * {@link #setOnFirstWrite} (a write to a closed file keeps its own Local History row, so it does not).
     */
    synchronized void beforeAgentWrite(Path file, @Nullable String text, boolean label) {
        Turn t = acp;
        if (t == null) {
            t = acp = start("AI Agent"); // a write outside a prompt (none should come): still recorded
        }
        if (remember(t, file, text) && label && text != null) {
            firstWrite(t, file, text);
        }
    }

    /** Before an MCP edit of {@code file} by {@code agent}: its text then. */
    synchronized void beforeMcpEdit(String agent, Path file, String text) {
        long now = clock.getAsLong();
        Turn t = mcp;
        if (t == null || now - lastMcpEdit > MCP_QUIET_MILLIS || !t.agent().equals(agent)) {
            t = mcp = start(agent);
        }
        lastMcpEdit = now;
        if (remember(t, file, text)) {
            firstWrite(t, file, text);
        }
    }

    /** The turns that changed files, newest first. */
    synchronized List<Turn> turns() {
        List<Turn> out = new ArrayList<>();
        for (Turn t : turns) {
            if (!t.before().isEmpty()) {
                out.add(copy(t));
            }
        }
        return out;
    }

    /** The newest turn that changed files, or null. */
    synchronized @Nullable Turn latest() {
        List<Turn> all = turns();
        return all.isEmpty() ? null : all.get(0);
    }

    private Turn start(String agent) {
        Turn t = new Turn(next++, agent, clock.getAsLong(), new LinkedHashMap<>());
        turns.addFirst(t);
        while (turns.size() > KEPT) {
            Turn dropped = turns.removeLast();
            if (dropped == acp) {
                acp = null;
            }
            if (dropped == mcp) {
                mcp = null;
            }
        }
        return t;
    }

    /** Keeps {@code text} as {@code file}'s text before {@code t}; true on the turn's first write to it. */
    private static boolean remember(Turn t, Path file, @Nullable String text) {
        if (t.before().containsKey(file)) {
            return false;
        }
        t.before().put(file, text); // only the first write of the turn: what the file was before the turn
        return true;
    }

    private void firstWrite(Turn t, Path file, String text) {
        FirstWrite listener = onFirstWrite;
        if (listener != null) {
            listener.before(t, file, text);
        }
    }

    /** How a turn is named in a picker and in Local History: "Turn 3 · Claude Code". */
    static String name(Turn t) {
        return com.editora.i18n.Messages.tr("agent.changes.turnName", t.number(), t.agent());
    }

    private static Turn copy(Turn t) {
        return new Turn(
                t.number(),
                t.agent(),
                t.startedAt(),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(t.before())));
    }
}
