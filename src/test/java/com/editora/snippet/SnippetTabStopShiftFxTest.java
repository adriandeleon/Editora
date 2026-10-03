package com.editora.snippet;

import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tab stops that <em>touch</em> or <em>contain</em> one another, driven through a real {@link SnippetSession}:
 * a stop starting exactly where the active one ends, and stops nested inside the text typed over. Both
 * drifted — the final caret landed inside the text just typed, and a nested stop ended up selecting text
 * in front of the snippet.
 */
@Tag("fx")
class SnippetTabStopShiftFxTest {

    @BeforeAll
    static void boot() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // another FX test booted the toolkit in this JVM
        }
        Platform.setImplicitExit(false);
    }

    private static <T> T onFx(Callable<T> body) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(body.call());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "FX task did not complete");
        if (err.get() != null) {
            throw new AssertionError(err.get());
        }
        return out.get();
    }

    /** The body of the bundled snippet with {@code prefix} in {@code snippets/<language>.json}. */
    private static String bundled(String language, String prefix) throws Exception {
        try (InputStream in = SnippetSession.class.getResourceAsStream("/com/editora/snippets/" + language + ".json")) {
            for (JsonNode snippet : new ObjectMapper().readTree(in)) {
                if (prefix.equals(snippet.path("prefix").asText())) {
                    JsonNode body = snippet.path("body");
                    if (!body.isArray()) {
                        return body.asText();
                    }
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode line : body) {
                        sb.append(sb.length() == 0 ? "" : "\n").append(line.asText());
                    }
                    return sb.toString();
                }
            }
        }
        throw new AssertionError("no bundled " + language + " snippet with prefix " + prefix);
    }

    /** Types {@code text} one character at a time, the way a field without a mirror receives keystrokes. */
    private static void type(CodeArea area, String text) {
        for (int i = 0; i < text.length(); i++) {
            area.replaceSelection(String.valueOf(text.charAt(i)));
        }
    }

    @Test
    void theFinalCaretLandsAfterTextTypedIntoTheLastField() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            SnippetSession session =
                    new SnippetSession(area, SnippetParser.parse("val ${1:name} = ${2:value}", n -> null), 0, 0, "");
            session.next(); // $2, "value" selected
            assertEquals("value", area.getSelectedText());
            type(area, "compute()");
            session.next(); // past the last stop → $0
            assertEquals("val name = compute()", area.getText());
            assertFalse(session.isActive());
            assertEquals(area.getLength(), area.getCaretPosition(), "the caret is after the typed text");
            return null;
        });
    }

    @Test
    void aFieldDirectlyAfterTheActiveOneIsNotStretchedOverWhatIsTyped() throws Exception {
        String body = bundled("go", "forr"); // for ${1:_, }${2:v} := range ${3:v} {…
        onFx(() -> {
            CodeArea area = new CodeArea();
            SnippetSession session = new SnippetSession(area, SnippetParser.parse(body, n -> null), 0, 0, "");
            assertEquals("_, ", area.getSelectedText(), "precondition: $1 is the `_, ` in front of $2");
            type(area, "i, ");
            session.next();
            assertEquals("v", area.getSelectedText(), "$2 is still exactly its own placeholder");
            type(area, "item");
            session.next();
            assertEquals("v", area.getSelectedText(), "and $3 after it");
            assertTrue(area.getText().startsWith("for i, item := range v {"), area.getText());
            return null;
        });
    }

    @Test
    void typingOverAFieldThatContainsOthersNeverSelectsTextOutsideIt() throws Exception {
        String body = bundled("python", "tryef"); // except${2: ${3:Exception} as ${4:e}}:
        onFx(() -> {
            CodeArea area = new CodeArea();
            SnippetSession session = new SnippetSession(area, SnippetParser.parse(body, n -> null), 0, 0, "");
            assertEquals("pass", area.getSelectedText(), "precondition: $1 is the try body");
            session.next();
            assertEquals(" Exception as e", area.getSelectedText(), "precondition: $2 wraps $3 and $4");
            type(area, " OSError");
            // $3 and $4 were deleted with the text typed over. Shifted by the edit's delta they pointed
            // into the `try:` block; they are retired instead, so Tab goes straight to $5.
            session.next();
            assertEquals("raise", area.getSelectedText());
            assertTrue(area.getText().contains("except OSError:\n\traise"), area.getText());
            session.previous();
            assertEquals(" OSError", area.getSelectedText(), "back to $2, skipping the retired stops");
            return null;
        });
    }

    @Test
    void theNestedFieldsAreStillVisitedWhenTheOuterOneIsKept() throws Exception {
        String body = bundled("python", "tryef");
        onFx(() -> {
            CodeArea area = new CodeArea();
            SnippetSession session = new SnippetSession(area, SnippetParser.parse(body, n -> null), 0, 0, "");
            session.next(); // $2
            session.next(); // $3
            assertEquals("Exception", area.getSelectedText());
            type(area, "KeyError");
            session.next(); // $4
            assertEquals("e", area.getSelectedText());
            type(area, "err");
            session.next(); // $5
            assertEquals("raise", area.getSelectedText());
            assertTrue(area.getText().contains("except KeyError as err:"), area.getText());
            return null;
        });
    }
}
