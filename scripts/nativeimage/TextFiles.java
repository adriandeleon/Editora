package nativeimage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Text files as the earlier Python scripts read and wrote them, so outputs and oracles are unchanged on every
 * platform: reading turns {@code \r\n} and {@code \r} into {@code \n}, writing turns {@code \n} into the platform's
 * line separator (CRLF on Windows).
 */
final class TextFiles {

    private static final Pattern LINE_BREAK =
            Pattern.compile("\r\n|[\n\r\u000b\u000c\u001c\u001d\u001e\u0085  ]");

    private TextFiles() {}

    /** The file's text, UTF-8 (malformed bytes replaced), with universal newlines. */
    static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    /** Writes {@code text} as UTF-8 with the platform's line separator. */
    static void write(Path path, String text) throws IOException {
        Files.writeString(path, text.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
    }

    /** Python's {@code str.splitlines()}: no trailing empty line, and no line at all for empty text. */
    static List<String> lines(String text) {
        var lines = new ArrayList<>(List.of(LINE_BREAK.split(text, -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }
}
