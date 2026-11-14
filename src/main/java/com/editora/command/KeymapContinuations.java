package com.editora.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * The keys that can follow a pending multi-key prefix, for the which-key style hint the window shows while a
 * prefix such as {@code C-x} waits for its next key (KBD-14 in Pauta 6.0.1). Pure: it reads a snapshot of the
 * bindings, the active keymap's with the user's own layered on, and knows nothing of the window.
 */
public final class KeymapContinuations {

    private KeymapContinuations() {}

    /**
     * One key that can follow the prefix.
     *
     * @param token the key, as a raw keymap token ({@code C-s}, {@code v})
     * @param commandId the command the prefix and this key run, or null when the key only leads further
     * @param further how many commands lie further down when the key is itself a prefix ({@code C-x v} of
     *     {@code C-x v l}); 0 for a key that runs a command
     */
    public record Continuation(String token, @Nullable String commandId, int further) {

        /** Whether this key starts a longer sequence instead of running a command. */
        public boolean isPrefix() {
            return commandId == null;
        }
    }

    /**
     * Every key that can follow {@code prefix} in {@code bindings} (chord sequence → command id), sorted as
     * {@link #ORDER} sorts them. A key bound to a command and also the start of a longer sequence runs the
     * command, as the dispatcher does. A binding whose command {@code known} rejects (a plugin's command that is
     * not loaded) is left out, as it does nothing when pressed.
     */
    public static List<Continuation> after(Map<String, String> bindings, String prefix, Predicate<String> known) {
        String head = prefix + " ";
        Map<String, @Nullable String> commands = new LinkedHashMap<>();
        Map<String, Integer> further = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            String sequence = e.getKey();
            String id = e.getValue();
            if (!sequence.startsWith(head) || id == null || id.isBlank() || !known.test(id)) {
                continue;
            }
            String rest = sequence.substring(head.length());
            int space = rest.indexOf(' ');
            if (space < 0) {
                commands.put(rest, id);
            } else {
                String token = rest.substring(0, space);
                further.merge(token, 1, Integer::sum);
                commands.putIfAbsent(token, null);
            }
        }
        List<Continuation> out = new ArrayList<>(commands.size());
        for (Map.Entry<String, @Nullable String> e : commands.entrySet()) {
            String id = e.getValue();
            out.add(new Continuation(e.getKey(), id, id == null ? further.getOrDefault(e.getKey(), 0) : 0));
        }
        out.sort(ORDER);
        return out;
    }

    /**
     * The order the hint lists keys in, which-key's: by the key itself, ignoring modifiers and case, so
     * {@code f} sits beside {@code C-f}; then the key with fewer modifiers first; then the token, for a stable
     * order.
     */
    static final Comparator<Continuation> ORDER = Comparator.comparing(
                    (Continuation c) -> baseKey(c.token()).toLowerCase(Locale.ROOT))
            .thenComparingInt(c -> c.token().length() - baseKey(c.token()).length())
            .thenComparing(Continuation::token);

    /** The key of a token without its modifiers: {@code C-M-x} → {@code x}, {@code C--} → {@code -}. Pure. */
    static String baseKey(String token) {
        String key = token;
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String modifier : MODIFIERS) {
                if (key.startsWith(modifier) && key.length() > modifier.length()) {
                    key = key.substring(modifier.length());
                    stripped = true;
                }
            }
        }
        return key;
    }

    private static final List<String> MODIFIERS = List.of("C-", "M-", "Cmd-", "S-");
}
