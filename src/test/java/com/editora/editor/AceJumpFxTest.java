package com.editora.editor;

import java.util.List;
import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.stage.Stage;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AceJump end to end: pick a character, type the label of the occurrence you want, land on it — and every
 * way the mode ends without moving the caret.
 */
@Tag("fx")
class AceJumpFxTest {

    private static final String TEXT = "zebra apple\n  banana cherry\nplain";

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static EditorBuffer shown(Stage[] stage) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.getArea().replaceText(TEXT);
        buffer.getArea().moveTo(0);
        stage[0] = EditorFx.show(buffer, 640, 320);
        buffer.getArea().requestFocus();
        buffer.getNode().layout();
        return buffer;
    }

    private static AceJumpOverlay overlay(EditorBuffer buffer) {
        return EditorFx.field(buffer, "aceJump");
    }

    private static boolean active(EditorBuffer buffer) {
        return EditorFx.<Boolean>field(overlay(buffer), "active");
    }

    private static Map<String, Integer> labels(EditorBuffer buffer) {
        return EditorFx.field(overlay(buffer), "labelToOffset");
    }

    private static void type(EditorBuffer buffer, String character) {
        buffer.getArea().fireEvent(EditorFx.typed(character));
    }

    @Test
    void typingACharacterLabelsItsOccurrencesAndALabelJumpsThere() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown(stage);
            CodeArea area = buffer.getArea();

            buffer.startAceJump();
            assertTrue(active(buffer));
            assertTrue(overlay(buffer).isVisible());
            assertEquals(Boolean.TRUE, area.getProperties().get("editora.ownsKeys"), "the mode owns the keyboard");
            buffer.startAceJump(); // already active: nothing restarts
            area.fireEvent(EditorFx.pressed(KeyCode.DOWN, false, false));
            assertEquals(0, area.getCaretPosition(), "caret keys are swallowed while the mode is on");

            type(buffer, "A"); // case-insensitive: every "a" on screen
            Map<String, Integer> labels = labels(buffer);
            long count = TEXT.chars().filter(c -> c == 'a').count();
            assertEquals(count, labels.size(), "one label for each visible occurrence");
            assertTrue(labels.values().stream().allMatch(offset -> TEXT.charAt(offset) == 'a'));
            assertEquals(TEXT, area.getText(), "the typed character chose targets, it was not inserted");

            int banana = TEXT.indexOf("banana") + 1;
            String label = labels.entrySet().stream()
                    .filter(e -> e.getValue() == banana)
                    .findFirst()
                    .orElseThrow()
                    .getKey();
            for (char c : label.toCharArray()) {
                type(buffer, String.valueOf(Character.toUpperCase(c))); // labels match in either case
            }
            assertEquals(banana, area.getCaretPosition());
            assertFalse(active(buffer));
            assertFalse(overlay(buffer).isVisible());
            assertFalse(area.getProperties().containsKey("editora.ownsKeys"));
            assertEquals(TEXT, area.getText());
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void aSingleOccurrenceIsJumpedToAtOnceAndAMissEndsTheMode() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown(stage);
            CodeArea area = buffer.getArea();

            buffer.startAceJump();
            type(buffer, "z");
            assertEquals(TEXT.indexOf('z'), area.getCaretPosition(), "the only z: no label needed");
            assertFalse(active(buffer));

            area.moveTo(3);
            buffer.startAceJump();
            type(buffer, "q"); // not on screen
            assertFalse(active(buffer));
            assertEquals(3, area.getCaretPosition());

            // Escape and C-g cancel, before or after the character is chosen.
            buffer.startAceJump();
            area.fireEvent(EditorFx.pressed(KeyCode.ESCAPE, false, false));
            assertFalse(active(buffer));
            buffer.startAceJump();
            type(buffer, "a");
            assertTrue(active(buffer));
            area.fireEvent(EditorFx.pressed(KeyCode.G, false, true));
            assertFalse(active(buffer));
            assertEquals(3, area.getCaretPosition());

            // A key that is no label at all ends the mode too; control characters are ignored.
            buffer.startAceJump();
            type(buffer, "a");
            type(buffer, "\u0001");
            assertTrue(active(buffer));
            String unused = List.of("1", "2", "3").stream()
                    .filter(k -> labels(buffer).keySet().stream().noneMatch(l -> l.startsWith(k)))
                    .findFirst()
                    .orElseThrow();
            type(buffer, unused);
            assertFalse(active(buffer));
            assertEquals(3, area.getCaretPosition());

            // An edit moves the offsets out from under the labels: cancel rather than mis-jump.
            buffer.startAceJump();
            type(buffer, "a");
            assertTrue(active(buffer));
            area.insertText(0, "x");
            buffer.getNode().layout();
            assertFalse(active(buffer));
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void lineModeLabelsEveryVisibleLineAndJumpsToItsFirstWord() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown(stage);
            CodeArea area = buffer.getArea();
            area.moveTo(TEXT.length());
            buffer.getNode().layout();

            buffer.startAceJumpLine();
            assertTrue(active(buffer));
            Map<String, Integer> labels = labels(buffer);
            assertEquals(3, labels.size(), "three lines on screen");
            int secondLine = TEXT.indexOf("banana");
            assertTrue(labels.containsValue(secondLine), "an indented line is targeted at its first word");
            String label = labels.entrySet().stream()
                    .filter(e -> e.getValue() == secondLine)
                    .findFirst()
                    .orElseThrow()
                    .getKey();
            type(buffer, label);
            assertEquals(secondLine, area.getCaretPosition());
            assertFalse(active(buffer));

            // With one line there is nothing to choose between.
            area.replaceText("   only line");
            area.moveTo(0);
            buffer.getNode().layout();
            buffer.startAceJumpLine();
            assertFalse(active(buffer));
            assertEquals(3, area.getCaretPosition());
            stage[0].close();
            buffer.dispose();
        });
    }
}
