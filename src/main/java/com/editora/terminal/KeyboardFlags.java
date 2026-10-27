package com.editora.terminal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import org.jspecify.annotations.Nullable;

/**
 * The kitty keyboard protocol's progressive-enhancement flags, as programs have set them.
 *
 * <p>Two stacks, one per screen, as the specification requires: an editor on the alternate screen
 * can turn the protocol on there and off again on exit without knowing, or disturbing, what the
 * shell on the main screen asked for. The current flags are the top of the current screen's stack,
 * and zero — legacy encoding — when it is empty.
 *
 * <p>Written on the emulator thread as the requests arrive, read on the FX thread for every key, so
 * every access is synchronised.
 */
final class KeyboardFlags {

    /** The flags defined so far. Anything else a program sends is masked off. */
    public static final int ALL = 0b11111;

    /**
     * Deep enough for any honest nesting; bounded because a program could otherwise push forever.
     * The oldest entry goes first when it is full, as the specification says.
     */
    static final int MAX_DEPTH = 32;

    private final Deque<Integer> main = new ArrayDeque<>();
    private final Deque<Integer> alternate = new ArrayDeque<>();

    /** The flags in force on one screen. */
    public synchronized int current(boolean alternateScreen) {
        Integer top = stack(alternateScreen).peek();
        return top == null ? 0 : top;
    }

    /**
     * Applies a request.
     *
     * @param marker {@code >} push, {@code <} pop, {@code =} set, {@code ?} query
     * @param params as written: {@code "1"}, {@code "3;2"}, or empty for the defaults
     * @return the bytes to answer with — only a query has an answer — or null
     */
    public synchronized byte @Nullable [] apply(char marker, String params, boolean alternateScreen) {
        Deque<Integer> stack = stack(alternateScreen);
        int[] p = numbers(params);
        switch (marker) {
            case '>' -> {
                if (stack.size() >= MAX_DEPTH) stack.removeLast();
                stack.push(p[0] & ALL);
            }
            case '<' -> {
                // Popping past the bottom empties the stack, which is legacy mode.
                for (int n = Math.max(1, p[0]); n > 0 && !stack.isEmpty(); n--) stack.pop();
            }
            case '=' -> {
                int flags = p[0] & ALL;
                int now = stack.isEmpty() ? 0 : stack.peek();
                int next = switch (p[1]) {
                    case 2 -> now | flags;
                    case 3 -> now & ~flags;
                    default -> flags;
                };
                if (stack.isEmpty()) stack.push(next);
                else {
                    stack.pop();
                    stack.push(next);
                }
            }
            case '?' -> {
                return ("\u001b[?" + current(alternateScreen) + "u").getBytes(StandardCharsets.US_ASCII);
            }
            default -> {}
        }
        return null;
    }

    /** A full reset: both screens back to legacy encoding. */
    public synchronized void reset() {
        main.clear();
        alternate.clear();
    }

    private Deque<Integer> stack(boolean alternateScreen) {
        return alternateScreen ? alternate : main;
    }

    /**
     * The first two parameters, each defaulting as the specification says: 0 for the flags (and
     * for a pop, where 0 is read as 1), and 1 for the mode. A sub-parameter after a colon is ignored.
     */
    static int[] numbers(String params) {
        int[] out = {0, 1};
        if (params == null || params.isEmpty()) return out;
        String[] fields = params.split(";", -1);
        for (int i = 0; i < Math.min(2, fields.length); i++) {
            String field = fields[i].split(":", 2)[0];
            if (field.isEmpty()) continue;
            try {
                out[i] = Integer.parseInt(field);
            } catch (NumberFormatException tooLong) {
                // Digits only reach here, so only an overflow can fail; it is not a mode anyone set.
                out[i] = i == 0 ? 0 : 1;
            }
        }
        return out;
    }
}
