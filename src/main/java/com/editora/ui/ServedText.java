package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * What an agent was last shown of each document, so a whole-document write can be told apart from an
 * overwrite of text the agent has never seen.
 *
 * <p>Neither ACP's {@code fs/write_text_file} nor MCP's whole-buffer replacement carries a document version:
 * the agent sends "the file is now this", computed from whatever it read earlier. Everything typed between
 * that read and the write was replaced without notice. The editor therefore remembers the text it
 * served per document and, at the write, compares it with the text that is there now.
 *
 * <p>Thread-safe; holds the text served (at most {@value #MAX_CHARS} characters in all), never hashed. Keys
 * are compared with {@code equals} (a path key, or the buffer
 * itself for an untitled one).
 */
final class ServedText {

    /** Whether a whole-document write may replace the current text. */
    enum Verdict {
        /** The agent's last read (or its own last write) is what is there now. */
        CURRENT,
        /** The text changed after the agent last read it. */
        CHANGED_SINCE_READ,
        /** The agent never read this document through the editor, and it holds unsaved changes. */
        UNREAD_UNSAVED;

        /**
         * The reason handed back to the agent — it names the remedy — or {@code null} when the write may go on.
         *
         * @param name the document, as the agent named it
         * @param readWith how this agent reads the editor's copy (the protocol call), e.g. {@code read_buffer}
         */
        @Nullable
        String refusal(String name, String readWith) {
            return switch (this) {
                case CURRENT -> null;
                case CHANGED_SINCE_READ ->
                    "Refused: " + name + " changed after you last read it, so this write would discard those"
                            + " changes. Read it again (" + readWith + "), re-apply your change to the current"
                            + " text, and retry.";
                case UNREAD_UNSAVED ->
                    "Refused: " + name + " is open in the editor with unsaved changes that you have not read,"
                            + " so this write would discard them. Read the editor's copy first (" + readWith
                            + "), apply your change to that text, and retry.";
            };
        }
    }

    private static final int MAX_ENTRIES = 2048;

    /** At most this many characters are kept in all; the oldest documents are forgotten first. */
    private static final long MAX_CHARS = 32L * 1024 * 1024;

    private final Map<Object, String> texts;

    private long chars;

    /** Keyed by values that stay meaningful on their own (path keys): the most recent {@value #MAX_ENTRIES}. */
    ServedText() {
        this(new LinkedHashMap<>(64, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, String> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    }

    /** Over a caller-chosen map — a {@link java.util.WeakHashMap} when the keys are the documents themselves. */
    ServedText(Map<Object, String> store) {
        this.texts = store;
    }

    /**
     * Records that the agent now knows {@code text} as the content of {@code key} (it read it, or wrote it). The
     * text itself is kept, not a digest: this runs on the FX thread, where no document is hashed (PERF-01, Pauta
     * 6.2.0); comparing it later is a walk over the two texts.
     */
    synchronized void served(Object key, String text) {
        String before = texts.put(key, text == null ? "" : text);
        chars += (text == null ? 0 : text.length()) - (before == null ? 0 : before.length());
        if (chars > MAX_CHARS) {
            chars = 0;
            var it = texts.entrySet().iterator();
            long total = texts.values().stream().mapToLong(String::length).sum();
            while (total > MAX_CHARS && it.hasNext()) {
                var e = it.next();
                if (e.getKey().equals(key)) {
                    continue; // the document just served stays
                }
                total -= e.getValue().length();
                it.remove();
            }
            chars = total;
        }
    }

    /**
     * Judges a whole-document write to {@code key}, whose text is {@code current} right now.
     *
     * @param unsavedChanges whether {@code current} differs from the file on disk — the only copy of the
     *     difference is then in the editor, where an agent that read the file by its own means never saw it
     */
    synchronized Verdict check(Object key, @Nullable String current, boolean unsavedChanges) {
        String seen = texts.get(key);
        if (seen == null) {
            return unsavedChanges ? Verdict.UNREAD_UNSAVED : Verdict.CURRENT;
        }
        return sameText(seen, current == null ? "" : current) ? Verdict.CURRENT : Verdict.CHANGED_SINCE_READ;
    }

    /** Whether {@code text} is exactly what the agent was last shown of {@code key} (false when never shown). */
    synchronized boolean knows(Object key, String text) {
        String seen = texts.get(key);
        return seen != null && sameText(seen, text == null ? "" : text);
    }

    /** Forgets {@code key} (its document is gone). */
    synchronized void forget(Object key) {
        String gone = texts.remove(key);
        chars -= gone == null ? 0 : gone.length();
    }

    /** Forgets everything: a new agent session knows nothing of what the previous one read. */
    synchronized void clear() {
        texts.clear();
        chars = 0;
    }

    /**
     * Whether {@code a} and {@code b} are the same text. Line terminators do not count: the same file is served
     * with its own terminators while it has no buffer and with {@code \n} once it is open, and that is not a
     * change anyone typed. A walk over both, no copy.
     */
    static boolean sameText(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i);
            char y = b.charAt(j);
            if (x == '\r' || y == '\r') {
                // \r\n and a lone \r both stand for \n
                if (x == '\r') {
                    i += i + 1 < a.length() && a.charAt(i + 1) == '\n' ? 2 : 1;
                    x = '\n';
                } else {
                    i++;
                }
                if (y == '\r') {
                    j += j + 1 < b.length() && b.charAt(j + 1) == '\n' ? 2 : 1;
                    y = '\n';
                } else {
                    j++;
                }
                if (x != y) {
                    return false;
                }
                continue;
            }
            if (x != y) {
                return false;
            }
            i++;
            j++;
        }
        return i == a.length() && j == b.length();
    }
}
