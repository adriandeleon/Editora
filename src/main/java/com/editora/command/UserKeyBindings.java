package com.editora.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The user's own key bindings, kept once for every operating system: per keymap, each command the user rebound
 * and the chords they gave it (none at all when they unbound it). Pure; no toolkit.
 *
 * <p>The keymap itself works on <em>overrides</em>, chord to command with a blank command meaning "unbound"
 * ({@link KeymapManager#applyOverrides}). Those cannot be shared between systems: a rebind also unbinds the
 * command's default chords, and the defaults are {@code Cmd} chords on macOS and {@code Ctrl} chords elsewhere,
 * so a Mac's unbind entries unbind nothing on Linux and the command ends up bound twice. So what is stored is
 * the intent, by command, and each system's overrides are {@linkplain #derive derived} from it against that
 * system's own defaults; an edit made through the overrides is {@linkplain #edit written back} as intent.
 *
 * <p>Chords are stored as Windows and Linux write them. A keymap with a macOS variant ({@code vscode.mac.json})
 * puts {@code Cmd} where the other has {@code Ctrl}, and the user's chords are read the same way there; the Emacs
 * keymap has no variant and its chords are read as written. A chord recorded on a Mac that the other systems
 * cannot type (one that uses the real Control key in a variant keymap, or Command in Emacs) is kept apart, for
 * macOS only.
 */
public final class UserKeyBindings {

    private UserKeyBindings() {}

    /** Modifier prefixes in the order {@link KeyDispatcher#chord} writes them. */
    private static final String[] PREFIXES = {"C-", "M-", "Cmd-", "S-"};

    private static final int CTRL = 1;
    private static final int CMD = 1 << 2;

    /**
     * Chords macOS takes before any application sees them (Spotlight, the application switcher, Force Quit):
     * a binding that lands on one could never fire there, so it is not used on a Mac.
     */
    private static final Set<String> MAC_RESERVED = Set.of("Cmd-space", "Cmd-tab", "Cmd-S-tab", "M-Cmd-escape");

    /**
     * One keymap's bindings: {@code shared} for every system, in Windows/Linux notation, and {@code macOnly}
     * for the chords only a Mac can type, in macOS notation. Command id to chords; an empty list is "unbound".
     */
    public record Store(Map<String, List<String>> shared, Map<String, List<String>> macOnly) {

        public static Store empty() {
            return new Store(new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        public boolean isEmpty() {
            return shared.isEmpty() && macOnly.isEmpty();
        }

        Store copy() {
            return new Store(new LinkedHashMap<>(shared), new LinkedHashMap<>(macOnly));
        }
    }

    /** A binding of the user's that this system cannot use: a chord it has no key for, or one it keeps for itself. */
    public record Skipped(String commandId, String chord) {}

    /** A system's overrides and the bindings left out of them. */
    public record Derived(Map<String, String> overrides, List<Skipped> skipped) {}

    // ---- the two notations ----

    private static int modifierMask(String token) {
        int mask = 0;
        int at = 0;
        for (int i = 0; i < PREFIXES.length; i++) {
            String p = PREFIXES[i];
            // A prefix only counts while a key is left after it: "C--" is Ctrl + the minus key.
            if (token.startsWith(p, at) && token.length() > at + p.length()) {
                mask |= 1 << i;
                at += p.length();
            }
        }
        return mask;
    }

    private static String key(String token) {
        int at = 0;
        for (String p : PREFIXES) {
            if (token.startsWith(p, at) && token.length() > at + p.length()) {
                at += p.length();
            }
        }
        return token.substring(at);
    }

    private static String token(int mask, String key) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < PREFIXES.length; i++) {
            if ((mask & (1 << i)) != 0) {
                out.append(PREFIXES[i]);
            }
        }
        return out.append(key).toString();
    }

    /**
     * A chord in shared notation as macOS types it in a keymap: {@code Cmd} for {@code Ctrl} where the keymap
     * has a macOS variant, the chord itself where it has none.
     */
    public static String toMac(String chord, boolean variant) {
        if (!variant) {
            return chord;
        }
        List<String> out = new ArrayList<>();
        for (String t : chord.split(" ")) {
            int mask = modifierMask(t);
            out.add((mask & CTRL) != 0 ? token((mask & ~CTRL) | CMD, key(t)) : t);
        }
        return String.join(" ", out);
    }

    /**
     * A chord recorded on macOS in shared notation, or null when Windows and Linux have no key for it: in a
     * variant keymap {@code Cmd} becomes {@code Ctrl} and a chord with the real Control key has no other form;
     * in a keymap without a variant a chord is its own form unless it uses {@code Cmd}.
     */
    public static @Nullable String toShared(String macChord, boolean variant) {
        List<String> out = new ArrayList<>();
        for (String t : macChord.split(" ")) {
            int mask = modifierMask(t);
            if (variant) {
                if ((mask & CTRL) != 0) {
                    return null;
                }
                out.add((mask & CMD) != 0 ? token((mask & ~CMD) | CTRL, key(t)) : t);
            } else {
                if ((mask & CMD) != 0) {
                    return null;
                }
                out.add(t);
            }
        }
        return String.join(" ", out);
    }

    private static @Nullable List<String> allToShared(List<String> macChords, boolean variant) {
        List<String> out = new ArrayList<>();
        for (String chord : macChords) {
            String shared = toShared(chord, variant);
            if (shared == null) {
                return null;
            }
            out.add(shared);
        }
        return out;
    }

    // ---- from the store to a system's overrides ----

    /**
     * The overrides of the system described by {@code mac}, for a keymap whose stock bindings (plugins'
     * included) there are {@code base}. Each command in the store loses the default chords it was not given
     * and gains the chords it was, taking a chord over from another command exactly as it did where the user
     * made the binding. A chord this system cannot type, or that macOS keeps for itself, is
     * {@linkplain Derived#skipped left out}; a command all of whose chords are left out keeps its defaults here.
     */
    public static Derived derive(Store store, Map<String, String> base, boolean mac, boolean variant) {
        Map<String, List<String>> wanted = new LinkedHashMap<>();
        store.shared().forEach((command, chords) -> {
            List<String> here = new ArrayList<>();
            for (String chord : chords) {
                here.add(mac ? toMac(chord, variant) : chord);
            }
            wanted.put(command, here);
        });
        if (mac) {
            store.macOnly().forEach((command, chords) -> wanted.put(command, new ArrayList<>(chords)));
        }

        List<Skipped> skipped = new ArrayList<>();
        Map<String, List<String>> used = new LinkedHashMap<>();
        wanted.forEach((command, chords) -> {
            List<String> kept = new ArrayList<>();
            for (String chord : chords) {
                if (usable(chord, mac)) {
                    kept.add(chord);
                } else {
                    skipped.add(new Skipped(command, chord));
                }
            }
            if (!kept.isEmpty() || chords.isEmpty()) {
                used.put(command, kept); // else nothing of it can be used here: the command keeps its defaults
            }
        });

        Map<String, String> overrides = new LinkedHashMap<>();
        used.forEach((command, chords) -> defaultChords(base, command).forEach(d -> {
            if (!chords.contains(d)) {
                overrides.put(d, KeymapManager.UNBIND);
            }
        }));
        used.forEach((command, chords) -> chords.forEach(chord -> overrides.put(chord, command)));
        return new Derived(overrides, skipped);
    }

    /** Whether this system can use {@code chord}: it has the keys for it, and does not keep it for itself. */
    private static boolean usable(String chord, boolean mac) {
        return ChordFormat.typable(chord, mac) && !(mac && MAC_RESERVED.contains(chord));
    }

    // ---- from overrides back to the store ----

    /**
     * The commands a set of overrides changes, each with the chords it ends up with: its defaults that are
     * not unbound or taken by another command, and the chords bound to it. A command left exactly at its
     * defaults is not in the answer.
     */
    public static Map<String, List<String>> byCommand(Map<String, String> overrides, Map<String, String> base) {
        Map<String, List<String>> bound = new LinkedHashMap<>();
        Set<String> touched = new LinkedHashSet<>();
        overrides.forEach((chord, command) -> {
            if (command == null || command.isBlank()) {
                String owner = base.get(chord);
                if (owner != null) {
                    touched.add(owner);
                }
            } else {
                bound.computeIfAbsent(command, k -> new ArrayList<>()).add(chord);
                touched.add(command);
            }
        });
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String command : touched) {
            List<String> defaults = defaultChords(base, command);
            List<String> chords = new ArrayList<>();
            for (String d : defaults) {
                String now = overrides.get(d);
                if (now == null || now.equals(command)) {
                    chords.add(d);
                }
            }
            for (String chord : bound.getOrDefault(command, List.of())) {
                if (!chords.contains(chord)) {
                    chords.add(chord);
                }
            }
            if (!chords.equals(defaults)) {
                out.put(command, chords);
            }
        }
        return out;
    }

    /**
     * The store after an edit made on one system: {@code newOverrides} replace that system's overrides, and
     * only the commands the edit changed are rewritten, so a binding this system does not use (it collides
     * here) and a Mac's own chords survive an edit made elsewhere. A command reset to its defaults leaves the
     * store altogether.
     */
    public static Store edit(
            Store old, Map<String, String> base, boolean mac, boolean variant, Map<String, String> newOverrides) {
        Map<String, List<String>> before =
                byCommand(derive(old, base, mac, variant).overrides(), base);
        Map<String, List<String>> after = byCommand(newOverrides, base);
        Store out = old.copy();
        Set<String> commands = new LinkedHashSet<>(before.keySet());
        commands.addAll(after.keySet());
        for (String command : commands) {
            List<String> now = after.get(command);
            if (Objects.equals(before.get(command), now)) {
                continue;
            }
            if (now == null) {
                out.shared().remove(command);
                out.macOnly().remove(command);
            } else if (!mac) {
                out.shared().put(command, now);
            } else {
                List<String> shared = allToShared(now, variant);
                if (shared != null) {
                    out.shared().put(command, shared);
                    out.macOnly().remove(command);
                } else {
                    out.macOnly().put(command, now);
                }
            }
        }
        return out;
    }

    /** {@code store} without any binding of {@code commandId}: a command that no longer exists (a deleted macro). */
    public static Store withoutCommand(Store store, String commandId) {
        Store out = store.copy();
        out.shared().remove(commandId);
        out.macOnly().remove(commandId);
        return out;
    }

    // ---- from the two per-system maps kept before ----

    /**
     * The store for a keymap whose overrides were kept apart by system: {@code windowsLinux} against that
     * keymap's stock bindings there, {@code mac} against its macOS ones. Nothing is lost: a binding both
     * systems agree on is stored once, one made on a single system becomes everyone's (or stays the Mac's own
     * when the others cannot type it), and where the two differ the Mac keeps its own beside the shared one.
     */
    public static Store fromSplit(
            Map<String, String> windowsLinux,
            Map<String, String> mac,
            Map<String, String> baseWindowsLinux,
            Map<String, String> baseMac,
            boolean variant) {
        Store out = Store.empty();
        out.shared().putAll(byCommand(windowsLinux, baseWindowsLinux));
        byCommand(mac, baseMac).forEach((command, chords) -> {
            List<String> shared = allToShared(chords, variant);
            List<String> existing = out.shared().get(command);
            if (shared == null) {
                out.macOnly().put(command, chords);
            } else if (existing == null) {
                out.shared().put(command, shared);
            } else if (!existing.equals(shared)) {
                out.macOnly().put(command, chords);
            }
        });
        return out;
    }

    private static List<String> defaultChords(Map<String, String> base, String commandId) {
        return KeybindingEdits.defaultChords(base, commandId);
    }
}
