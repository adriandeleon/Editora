package com.editora.terminal;

import java.io.IOException;

import com.jediterm.terminal.TerminalDataStream;

/**
 * Takes the kitty keyboard protocol's mode sequences out of the stream before JediTerm sees them,
 * and notices a full reset.
 *
 * <p>The four sequences are {@code CSI > flags u} (push), {@code CSI < n u} (pop), {@code CSI =
 * flags ; mode u} (set) and {@code CSI ? u} (query). Left to JediTerm, every one of them would be
 * read as {@code CSI u} — SCORC, restore the saved cursor — with its private marker ignored: a
 * program enabling the protocol would have its cursor thrown somewhere else instead. Nothing else
 * with a final {@code u} carries one of those markers, so the match is exact.
 *
 * <p>{@code ESC c} (RIS) is reported and passed on: the emulator still resets, and the keyboard
 * mode resets with it, which is what makes typing {@code reset} recover a shell left in a mode by
 * a program that crashed.
 *
 * <p>A wrapper around the stream the emulator reads rather than a tap on the connector: the
 * emulator acts on each character before pulling the next, so a mode change takes effect at its
 * place in the output, with the screen it was meant for already current.
 */
final class KeyboardModeInterceptor implements TerminalDataStream {

    /** What a program asked of the keyboard. */
    interface Listener {
        /**
         * @param marker one of {@code > < = ?}
         * @param params the parameters as written, possibly empty: {@code "1"}, {@code "5;2"}
         */
        void keyboardMode(char marker, String params);

        /** A full reset (RIS). */
        void reset();
    }

    private static final char ESC = 0x1B;

    /** Longer than any parameter list these take; a run past it is not one of ours. */
    private static final int MAX_PARAMS = 16;

    private final TerminalDataStream in;
    private final Listener listener;

    KeyboardModeInterceptor(TerminalDataStream in, Listener listener) {
        this.in = in;
        this.listener = listener;
    }

    @Override
    public char getChar() throws IOException {
        while (true) {
            char c = in.getChar();
            if (c != ESC) return c;
            char next = in.getChar();
            if (next == 'c') {
                listener.reset();
                in.pushChar(next);
                return c;
            }
            if (next != '[' || !takeMode()) {
                if (next != '[') in.pushChar(next);
                return c;
            }
            // Taken: the emulator is owed whatever follows it.
        }
    }

    /**
     * Having read {@code ESC [}, takes a mode sequence. Anything else is put back whole — the
     * {@code [} included — so the emulator meets exactly what was sent after the ESC it is given.
     */
    private boolean takeMode() throws IOException {
        StringBuilder read = new StringBuilder();
        char marker = in.getChar();
        read.append(marker);
        boolean ours = marker == '>' || marker == '<' || marker == '=' || marker == '?';
        if (ours) {
            char c = in.getChar();
            read.append(c);
            while (((c >= '0' && c <= '9') || c == ';' || c == ':') && read.length() <= MAX_PARAMS) {
                c = in.getChar();
                read.append(c);
            }
            if (c == 'u') {
                listener.keyboardMode(marker, read.substring(1, read.length() - 1));
                return true;
            }
        }
        for (int i = read.length() - 1; i >= 0; i--) in.pushChar(read.charAt(i));
        in.pushChar('[');
        return false;
    }

    @Override
    public void pushChar(char c) throws IOException {
        in.pushChar(c);
    }

    @Override
    public String readNonControlCharacters(int maxChars) throws IOException {
        return in.readNonControlCharacters(maxChars);
    }

    @Override
    public void pushBackBuffer(char[] bytes, int length) throws IOException {
        in.pushBackBuffer(bytes, length);
    }

    @Override
    public boolean isEmpty() {
        return in.isEmpty();
    }
}
