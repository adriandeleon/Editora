package com.editora.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RichTextFX notifies every {@code estimatedScrollY} listener on every scroll step, so the per-scroll features
 * of a view (format bar, AI actions bar, sticky scroll, semantic-token refresh) share one listener in
 * {@code EditorBuffer.installFormatBarListeners} rather than registering one each (K5 in the 2026-10 perf
 * review). This pins the count so a new feature joins that listener instead of adding a dispatch per step.
 */
class ScrollListenerPolicyTest {

    @Test
    void theFormatBarInstallerRegistersOneVerticalScrollListener() throws IOException {
        String code = Files.readString(Path.of("src/main/java/com/editora/editor/EditorBuffer.java"));
        Matcher start = Pattern.compile("private void installFormatBarListeners\\(CodeArea a\\) \\{")
                .matcher(code);
        assertTrue(start.find(), "installFormatBarListeners is where the per-view scroll listeners live");
        String body = methodBody(code, start.end());
        Matcher listeners = Pattern.compile("estimatedScrollYProperty\\(\\)\\s*\\.addListener\\(")
                .matcher(body);
        int count = 0;
        while (listeners.find()) {
            count++;
        }
        assertEquals(1, count, "one estimatedScrollY listener per view in installFormatBarListeners");
    }

    /** The text up to the brace that closes the method whose opening brace precedes {@code from}. */
    private static String methodBody(String code, int from) {
        int depth = 1;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(from, i);
            }
        }
        throw new AssertionError("unbalanced braces");
    }
}
