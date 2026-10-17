package com.editora.ui;

import javafx.event.ActionEvent;
import javafx.scene.control.Spinner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void textThatIsNotAValueIsKeptMarkedAndNotCommittedAndTheSpinnerKeepsWorking() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Spinner<Integer> spinner = IntSpinners.editable(1, 16, 4, 1);
            var danger = atlantafx.base.theme.Styles.STATE_DANGER;

            for (String typed : java.util.List.of("abc", "-5", "400", "99999999999", "1.5")) {
                spinner.getEditor().setText(typed);
                spinner.getEditor().fireEvent(new ActionEvent()); // Enter
                assertEquals(4, spinner.getValue(), typed + " is not committed");
                assertEquals(typed, spinner.getEditor().getText(), typed + " stays to be fixed");
                assertTrue(spinner.getEditor().getPseudoClassStates().contains(danger), typed + " is marked");
                assertEquals(
                        com.editora.i18n.Messages.tr("settings.number.range", 1, 16),
                        spinner.getTooltip().getText(),
                        "the mark says what is taken");
            }

            spinner.getEditor().setText("");
            spinner.commitValue(); // what the spinner does itself when focus leaves it
            assertEquals(4, spinner.getValue(), "an empty field is not a null value");
            spinner.increment(1); // used to throw a NullPointerException
            assertEquals(5, spinner.getValue());
            assertEquals("5", spinner.getEditor().getText(), "an arrow sets a value in force");
            assertFalse(spinner.getEditor().getPseudoClassStates().contains(danger), "and the mark goes");

            spinner.getEditor().setText(" 8 ");
            spinner.getEditor().fireEvent(new ActionEvent());
            assertEquals(8, spinner.getValue());
            assertEquals("8", spinner.getEditor().getText());
            assertNull(spinner.getTooltip());
        });
    }
}
