package com.editora.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, unit-tested logic for the keybinding editor: given the active keymap's <b>base</b> bindings
 * (chord&nbsp;&rarr;&nbsp;commandId, before user overrides) and the current <b>user overrides</b> map,
 * compute the new overrides for a rebind / clear / reset. A blank override value is the
 * {@link KeymapManager#UNBIND} sentinel that suppresses a base default. No toolkit dependency; the
 * controller persists the returned map and reloads the shared keymap.
 */
public final class KeybindingEdits {

    private KeybindingEdits() {}

    /** All chord sequences the base keymap binds to {@code commandId}, in iteration order. */
    public static List<String> defaultChords(Map<String, String> base, String commandId) {
        List<String> out = new ArrayList<>();
        base.forEach((seq, id) -> {
            if (id.equals(commandId)) {
                out.add(seq);
            }
        });
        return out;
    }

    /**
     * Bind {@code commandId} to {@code newSeq}: drop the command's prior user bindings, suppress each base
     * default chord that isn't the new one (so the old shortcut stops firing), then bind the new chord.
     * A blank {@code newSeq} is treated as {@link #clear}.
     */
    public static Map<String, String> rebind(
            Map<String, String> base, Map<String, String> overrides, String commandId, String newSeq) {
        if (newSeq == null || newSeq.isBlank()) {
            return clear(base, overrides, commandId);
        }
        Map<String, String> result = withoutCommand(overrides, commandId);
        for (String def : defaultChords(base, commandId)) {
            if (!def.equals(newSeq)) {
                result.put(def, KeymapManager.UNBIND); // suppress the old default
            }
        }
        result.put(newSeq, commandId);
        return result;
    }

    /** Unbind {@code commandId} entirely: drop its user bindings and suppress every base default chord. */
    public static Map<String, String> clear(Map<String, String> base, Map<String, String> overrides, String commandId) {
        Map<String, String> result = withoutCommand(overrides, commandId);
        for (String def : defaultChords(base, commandId)) {
            result.put(def, KeymapManager.UNBIND);
        }
        return result;
    }

    /** Reset {@code commandId} to its keymap default: drop its user bindings and any default-suppressors. */
    public static Map<String, String> reset(Map<String, String> base, Map<String, String> overrides, String commandId) {
        Map<String, String> result = withoutCommand(overrides, commandId);
        for (String def : defaultChords(base, commandId)) {
            if (KeymapManager.UNBIND.equals(result.get(def))) {
                result.remove(def); // let the base default reappear
            }
        }
        return result;
    }

    /** A copy of {@code overrides} with every entry that <em>binds</em> {@code commandId} removed. */
    private static Map<String, String> withoutCommand(Map<String, String> overrides, String commandId) {
        Map<String, String> result = new LinkedHashMap<>(overrides);
        result.values().removeIf(commandId::equals);
        return result;
    }
}
