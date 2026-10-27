package com.editora.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * What the program-facing operating-system commands mean — a notification (OSC 9, OSC 777) and a clipboard write (OSC
 * 52) — parsed, and nothing more. {@link OscTap} takes them out of the stream; the view decides what to do about them.
 *
 * <p>Pure, so each is tested on the exact strings programs send. Ported from Termina.
 */
final class OscSequences {

    /** A program setting the clipboard, base64. */
    static final int CLIPBOARD = 52;

    /**
     * A notification, iTerm2's form: the payload is the message. ConEmu reuses the number for a family of numbered
     * subcommands ({@code 9;4;…} is a progress bar, {@code 9;9;…} the directory), which {@link #notification} tells
     * apart.
     */
    static final int NOTIFY = 9;

    /** A notification, urxvt's form: {@code notify;title;body}. What foot, kitty and WezTerm also read. */
    static final int NOTIFY_RXVT = 777;

    /** A title longer than this is cut: it is a line in a desktop bubble, not a document. */
    static final int MAX_TITLE = 128;

    static final int MAX_BODY = 1024;

    private OscSequences() {}

    /** A desktop notification a program asked for. The title may be empty; the body is not. */
    record Notification(String title, String body) {}

    /**
     * The notification an OSC 9 or 777 asks for, cleaned: control characters out, both parts cut to a length a bubble
     * can show. Empty for anything else that arrives under those numbers — a ConEmu subcommand, a urxvt extension other
     * than {@code notify}, a message with nothing in it.
     */
    static Optional<Notification> notification(int code, String payload) {
        if (payload == null) return Optional.empty();
        String title;
        String body;
        if (code == NOTIFY) {
            if (conEmuSubcommand(payload) >= 0) return Optional.empty();
            title = "";
            body = payload;
        } else if (code == NOTIFY_RXVT) {
            String[] parts = payload.split(";", 3);
            if (parts.length < 2 || !parts[0].equals("notify")) return Optional.empty();
            title = parts[1];
            body = parts.length > 2 ? parts[2] : "";
            // "notify;Done" is a title with nothing under it; shown as the message, not as nothing.
            if (body.isBlank()) {
                body = title;
                title = "";
            }
        } else {
            return Optional.empty();
        }
        title = clean(title, MAX_TITLE);
        body = clean(body, MAX_BODY);
        return body.isEmpty() ? Optional.empty() : Optional.of(new Notification(title, body));
    }

    /**
     * The number of a ConEmu subcommand — digits alone, or digits and a semicolon — or -1 when the payload is an
     * ordinary message. "42 tests passed" is a message; "4;1;50" is a progress bar.
     */
    static int conEmuSubcommand(String payload) {
        int end = 0;
        while (end < payload.length() && end < 3 && Character.isDigit(payload.charAt(end))) end++;
        if (end == 0) return -1;
        if (end < payload.length() && payload.charAt(end) != ';') return -1;
        return Integer.parseInt(payload.substring(0, end));
    }

    /** Text fit for a bubble: no control characters, which a program could use to forge lines. */
    private static String clean(String text, int max) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), max));
        for (int i = 0; i < text.length() && out.length() < max; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                continue;
            }
            if (out.length() + Character.charCount(cp) > max) break;
            out.appendCodePoint(cp);
        }
        return out.toString().strip();
    }

    /**
     * The text an OSC 52 would put on the clipboard.
     *
     * <p>Empty for a query ({@code ?}), which is never answered: answering would hand the clipboard — passwords,
     * tokens, whatever was last copied anywhere — to any program that asks, including one on the far side of an
     * {@code ssh}. Empty too for data that is not base64 or not UTF-8, rather than putting mojibake on the clipboard.
     * The selection letters before the semicolon ({@code c}, {@code p}, {@code s}) are accepted and ignored: JavaFX has
     * one clipboard.
     */
    static Optional<String> clipboardText(String payload) {
        if (payload == null) return Optional.empty();
        int semicolon = payload.indexOf(';');
        if (semicolon < 0) return Optional.empty();
        String data = payload.substring(semicolon + 1).strip();
        if (data.isEmpty() || data.equals("?")) return Optional.empty();
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(data);
            var decoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            String text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
            return Optional.empty();
        }
    }
}
