package com.editora.agent.runtime;

import java.nio.file.Path;
import java.util.List;

/** The only native-agent document mutation boundary. Implementations serialize commits on their UI thread. */
public interface AgentDocuments {
    record Snapshot(Path path, String revision, String text, boolean dirty) {}

    /** Open-document identity without copying buffer contents on the UI thread. */
    record State(Path path, String revision, boolean dirty) {}

    record Edit(Path path, String revision, String oldText, String newText) {}

    Snapshot read(Path path, AgentCancellation cancellation) throws Exception;

    List<Snapshot> open(AgentCancellation cancellation) throws Exception;

    default List<State> states(AgentCancellation cancellation) throws Exception {
        return open(cancellation).stream()
                .map(snapshot -> new State(snapshot.path(), snapshot.revision(), snapshot.dirty()))
                .toList();
    }

    List<Snapshot> apply(List<Edit> edits, AgentCancellation cancellation) throws Exception;

    Snapshot create(Path path, String text, AgentCancellation cancellation) throws Exception;

    void save(List<Snapshot> snapshots, AgentCancellation cancellation) throws Exception;

    record Diagnostics(
            boolean available, int errors, String text, String freshness, long generation, Integer lspVersion) {
        public Diagnostics(boolean available, int errors, String text) {
            this(available, errors, text, available ? "UNKNOWN" : "UNAVAILABLE", 0, null);
        }
    }

    Diagnostics diagnostics(Path path, AgentCancellation cancellation) throws Exception;

    boolean saved(Snapshot snapshot, AgentCancellation cancellation) throws Exception;

    void showDiff(Path path, String before, String after, AgentCancellation cancellation) throws Exception;

    static String replacement(String text, Edit edit) {
        if (edit.oldText().isEmpty()) {
            return edit.newText();
        }
        int start = text.indexOf(edit.oldText());
        if (start < 0 || text.indexOf(edit.oldText(), start + 1) >= 0) {
            throw new IllegalArgumentException("old_text must occur exactly once in " + edit.path()
                    + "; found " + (start < 0 ? "no match" : "multiple matches")
                    + ". Reread this file and use a unique literal range.");
        }
        return text.substring(0, start)
                + edit.newText()
                + text.substring(start + edit.oldText().length());
    }
}
