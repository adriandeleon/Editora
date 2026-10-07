package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an agent was last shown of each document, so a whole-document write can be told apart from an
 * overwrite of text the agent has never seen.
 *
 * <p>Neither ACP's {@code fs/write_text_file} nor MCP's whole-buffer replacement carries a document version:
 * the agent sends "the file is now this", computed from whatever it read earlier. Everything typed between
 * that read and the write was replaced without notice. The editor therefore remembers a digest of the text it
 * served per document and, at the write, compares it with the text that is there now.
 *
 * <p>Thread-safe; holds digests, not text. Keys are compared with {@code equals} (a path key, or the buffer
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

        /** The reason handed back to the agent — it names the remedy — or {@code null} when the write may go on. */
        String refusal(String name) {
            return switch (this) {
                case CURRENT -> null;
                case CHANGED_SINCE_READ ->
                    "Refused: " + name + " changed in the editor after you last read it, so this write would discard"
                            + " those changes. Read it again, re-apply your change to the current text, and retry.";
                case UNREAD_UNSAVED ->
                    "Refused: " + name + " is open in the editor with unsaved changes that you have not read, so"
                            + " this write would discard them. Read it through the editor first, apply your change"
                            + " to that text, and retry.";
            };
        }
    }

    private static final int MAX_ENTRIES = 2048;

    private final Map<Object, byte[]> digests = new LinkedHashMap<>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, byte[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /** Records that the agent now knows {@code text} as the content of {@code key} (it read it, or wrote it). */
    synchronized void served(Object key, String text) {
        digests.put(key, digest(text));
    }

    /**
     * Judges a whole-document write to {@code key}, whose text is {@code current} right now.
     *
     * @param unsavedChanges whether {@code current} differs from the file on disk — the only copy of the
     *     difference is then in the editor, where an agent that read the file by its own means never saw it
     */
    synchronized Verdict check(Object key, String current, boolean unsavedChanges) {
        byte[] seen = digests.get(key);
        if (seen == null) {
            return unsavedChanges ? Verdict.UNREAD_UNSAVED : Verdict.CURRENT;
        }
        return Arrays.equals(seen, digest(current)) ? Verdict.CURRENT : Verdict.CHANGED_SINCE_READ;
    }

    /** Forgets {@code key} (its document is gone). */
    synchronized void forget(Object key) {
        digests.remove(key);
    }

    /** Forgets everything: a new agent session knows nothing of what the previous one read. */
    synchronized void clear() {
        digests.clear();
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
