package com.editora.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Pure, unit-tested logic for the keybinding editor: given the active keymap's <b>base</b> bindings
 * (chord&nbsp;&rarr;&nbsp;commandId, before user overrides) and the current <b>user overrides</b> map,
 * compute the new overrides for a rebind / clear / reset. A blank override value is the
 * {@link KeymapManager#UNBIND} sentinel that suppresses a base default. No toolkit dependency; the
 * controller persists the returned map and reloads the shared keymap.
 */
public final class KeybindingEdits {

    private KeybindingEdits() {}

    /** How a proposed chord collides with an existing binding. */
    public enum ConflictKind {
        /** Same chord — the proposed rebind takes the existing binding over. */
        EXACT,
        /** The proposed chord is a prefix of the existing multi-key chord, so binding it makes the longer
         *  chord unreachable (the prefix now resolves to a command before the extra keys can be typed). */
        SHADOWS,
        /** An existing chord is a prefix of the proposed one, so the proposed chord can never fire (the
         *  shorter existing chord resolves first). */
        UNREACHABLE
    }

    /** A binding the proposed chord would collide with. */
    public record Conflict(String chord, String commandId, ConflictKind kind) {}

    /**
     * The existing bindings that binding {@code proposed} to {@code forCommandId} would collide with. Chords
     * are space-joined key tokens ({@code "C-x C-s"}); a prefix is a whole-token prefix. Bindings of
     * {@code forCommandId} itself are ignored (rebinding a command over its own chord is not a conflict).
     *
     * <p>This is what turns a silent footgun into a warning: recording a lone {@code C-x} used to draw no
     * warning (no <em>exact</em> match existed) yet dead-ended every {@code C-x …} binding — save, find,
     * commit, quit — because the dispatcher resolves {@code C-x} to a command before the second key arrives.
     */
    public static List<Conflict> conflicts(Map<String, String> active, String proposed, String forCommandId) {
        List<Conflict> out = new ArrayList<>();
        if (active == null || proposed == null || proposed.isBlank()) {
            return out;
        }
        String p = proposed.trim();
        active.forEach((chord, cmd) -> {
            if (cmd == null || cmd.equals(forCommandId) || KeymapManager.UNBIND.equals(cmd) || chord.isBlank()) {
                return;
            }
            if (chord.equals(p)) {
                out.add(new Conflict(chord, cmd, ConflictKind.EXACT));
            } else if (chord.startsWith(p + " ")) {
                out.add(new Conflict(chord, cmd, ConflictKind.SHADOWS)); // proposed is a prefix of this chord
            } else if (p.startsWith(chord + " ")) {
                out.add(new Conflict(chord, cmd, ConflictKind.UNREACHABLE)); // this chord is a prefix of proposed
            }
        });
        return out;
    }

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

    /**
     * Reset {@code commandId} to its keymap default: drop its user bindings and any override on one of its
     * default chords — a suppressor, or another command's rebind that took the chord over. Without the
     * second part a command whose chord had been taken stayed unbound after Reset, which said it was reset.
     * The caller asks first when {@link #taken} is not empty: the other command loses that chord.
     */
    public static Map<String, String> reset(Map<String, String> base, Map<String, String> overrides, String commandId) {
        Map<String, String> result = withoutCommand(overrides, commandId);
        for (String def : defaultChords(base, commandId)) {
            result.remove(def); // let the base default reappear
        }
        return result;
    }

    /** The default chords of {@code commandId} that a user override now gives to another command. Pure. */
    public static List<Conflict> taken(Map<String, String> base, Map<String, String> overrides, String commandId) {
        List<Conflict> out = new ArrayList<>();
        for (String def : defaultChords(base, commandId)) {
            String now = overrides.get(def);
            if (now != null && !now.isBlank() && !now.equals(commandId)) {
                out.add(new Conflict(def, now, ConflictKind.EXACT));
            }
        }
        return out;
    }

    /**
     * Whether {@code sequence} starts with a key that types text — a letter, digit, punctuation or Space with
     * no modifier but Shift. Bound, that key would run the command instead of typing its character
     * everywhere, so it cannot begin a shortcut. Pure.
     */
    public static boolean typesText(String sequence) {
        if (sequence == null || sequence.isBlank()) {
            return false;
        }
        String first = sequence.strip().split(" ")[0];
        if (first.startsWith("S-") && first.length() > 2) {
            first = first.substring(2);
        }
        for (String p : new String[] {"C-", "M-", "Cmd-"}) {
            if (first.startsWith(p) && first.length() > p.length()) {
                return false;
            }
        }
        return first.length() == 1 || first.equals("space");
    }

    /**
     * The overrides that take back a {@link #clear} or a {@link #reset} of {@code commandId}, or null when that is no
     * longer safe. {@code before} and {@code after} are the overrides around the removal, {@code now} the overrides
     * today, {@code activeNow} today's effective bindings (chord → command) and {@code chords} what the command was
     * bound to before the removal. Only the chords the removal changed are put back, so bindings made since for
     * other commands stay. Null when one of those chords was changed again since, when the command was bound again
     * since (the new binding would be dropped), or when one of {@code chords} is no longer free (another command
     * holds it, or a prefix of it, or a longer chord it would shadow). Pure.
     */
    public static @Nullable Map<String, String> restored(
            Map<String, String> before,
            Map<String, String> after,
            Map<String, String> now,
            Map<String, String> activeNow,
            String commandId,
            List<String> chords) {
        List<String> changed = new ArrayList<>();
        for (String chord : before.keySet()) {
            if (!Objects.equals(before.get(chord), after.get(chord))) {
                changed.add(chord);
            }
        }
        for (String chord : after.keySet()) {
            if (!before.containsKey(chord)) {
                changed.add(chord);
            }
        }
        for (String chord : changed) {
            if (!Objects.equals(now.get(chord), after.get(chord))) {
                return null; // that chord was bound, unbound or reset again since
            }
        }
        for (Map.Entry<String, String> e : now.entrySet()) {
            if (commandId.equals(e.getValue()) && !commandId.equals(after.get(e.getKey()))) {
                return null; // the command was given a binding since
            }
        }
        for (String chord : chords) {
            if (!conflicts(activeNow, chord, commandId).isEmpty()) {
                return null; // the key is taken
            }
        }
        Map<String, String> result = new LinkedHashMap<>(now);
        for (String chord : changed) {
            String was = before.get(chord);
            if (was == null) {
                result.remove(chord);
            } else {
                result.put(chord, was);
            }
        }
        return result;
    }

    /** A copy of {@code overrides} with every entry that <em>binds</em> {@code commandId} removed. */
    public static Map<String, String> withoutCommand(Map<String, String> overrides, String commandId) {
        Map<String, String> result = new LinkedHashMap<>(overrides);
        result.values().removeIf(commandId::equals);
        return result;
    }
}
