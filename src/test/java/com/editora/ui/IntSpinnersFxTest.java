package com.editora.ui;

import javafx.event.ActionEvent;
import javafx.scene.control.Spinner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Settings number fields must survive what people type into them. A stock editable spinner threw on
 * letters, committed {@code null} for an empty field (after which the arrows threw), and overflowed on a long
 * number.
 */
@Tag("fx")
class IntSpinnersFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void textThatIsNotANumberKeepsTheCurrentValueAndNumbersAreClamped() {
        assertEquals(4, IntSpinners.parse("abc", 4, 1, 16));
        assertEquals(4, IntSpinners.parse("", 4, 1, 16));
        assertEquals(4, IntSpinners.parse(null, 4, 1, 16));
        assertEquals(4, IntSpinners.parse("1.5", 4, 1, 16));
        assertEquals(8, IntSpinners.parse(" 8 ", 4, 1, 16));
        assertEquals(16, IntSpinners.parse("99", 4, 1, 16));
        assertEquals(1, IntSpinners.parse("-3", 4, 1, 16));
        assertEquals(16, IntSpinners.parse("99999999999", 4, 1, 16), "past int range");
        assertEquals(16, IntSpinners.parse("99999999999999999999999999", 4, 1, 16), "past long range");
        assertEquals(1, IntSpinners.parse("-99999999999999999999999999", 4, 1, 16));
    }

    @Test
    void typingLettersOrNothingLeavesAWorkingSpinnerShowingItsValue() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Spinner<Integer> spinner = IntSpinners.editable(1, 16, 4, 1);

            spinner.getEditor().setText("abc");
            spinner.getEditor().fireEvent(new ActionEvent()); // Enter
            assertEquals(4, spinner.getValue());
            assertEquals("4", spinner.getEditor().getText(), "the rejected text is replaced by the value in force");

            spinner.getEditor().setText("");
            spinner.commitValue(); // what the spinner does itself when focus leaves it
            assertEquals(4, spinner.getValue(), "an empty field is not a null value");
            spinner.increment(1); // used to throw a NullPointerException
            assertEquals(5, spinner.getValue());
            spinner.decrement(1);
            assertEquals(4, spinner.getValue());

            spinner.getEditor().setText("99999999999");
            spinner.getEditor().fireEvent(new ActionEvent());
            assertEquals(16, spinner.getValue());
            assertEquals("16", spinner.getEditor().getText());

            spinner.getEditor().setText("400"); // already at the maximum: no value change to redraw the text
            spinner.getEditor().fireEvent(new ActionEvent());
            assertEquals("16", spinner.getEditor().getText());
        });
    }
}
