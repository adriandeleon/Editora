package com.editora.sync;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** This machine's side of a sync: the live files of the config directory. */
public interface SyncTarget {

    /**
     * The files of {@code categories} as they are now: path relative to the config directory (forward
     * slashes) to text.
     */
    Map<String, String> read(Set<SyncCategory> categories) throws IOException;

    /**
     * Writes {@code changes} (path to new text, or to {@code null} for "delete") — but only when each of those
     * files still holds what {@code expected} says it held (absent from the map = did not exist). Returns
     * false, having written nothing, when one of them changed in the meantime.
     */
    boolean apply(Map<String, String> expected, Map<String, String> changes) throws IOException;
}
