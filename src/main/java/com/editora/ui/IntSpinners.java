package com.editora.ui;

import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.util.StringConverter;

import atlantafx.base.theme.Styles;

import static com.editora.i18n.Messages.tr;

/**
 * Editable whole-number spinners that cannot be typed into a broken state, and say so when typed text is not a
 * value they take.
 *
 * <p>A stock editable {@code Spinner<Integer>} converts its text with {@code IntegerStringConverter}: letters
 * (or a number too large for an {@code int}) throw out of the commit, and an empty field commits {@code null}
 * — after which the arrow buttons throw until the value is set again. Here text that is not a whole number in
 * range is not committed: the value in force stays, the typed text stays for the user to fix, and the field is
 * marked with the Settings rule for invalid input (the danger state and a tooltip giving the range). Typing
 * "-5" for a font size used to apply 6 to every editor without a word.
 */
final class IntSpinners {

    private IntSpinners() {}

    /** An editable spinner over {@code min..max} that steps by {@code step}. */
    static Spinner<Integer> editable(int min, int max, int initial, int step) {
        Spinner<Integer> spinner = new Spinner<>(min, max, initial, step);
        SpinnerValueFactory<Integer> factory = spinner.getValueFactory();
        factory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : Integer.toString(value);
            }

            @Override
            public Integer fromString(String text) {
                Integer current = factory.getValue();
                return accepts(text, min, max) ? parse(text, current == null ? initial : current, min, max) : current;
            }
        });
        spinner.setEditable(true);
        Tooltip reason = new Tooltip(tr("settings.number.range", min, max));
        // After each commit: text that is a value is shown as the value in force (" 8 " → "8"); any other text
        // stays, marked, until it is fixed or an arrow sets a value.
        Runnable settle = () -> {
            boolean ok = accepts(spinner.getEditor().getText(), min, max);
            spinner.getEditor().pseudoClassStateChanged(Styles.STATE_DANGER, !ok);
            spinner.setTooltip(ok ? null : reason);
            if (ok) {
                spinner.getEditor().setText(factory.getConverter().toString(factory.getValue()));
            }
        };
        spinner.getEditor().setOnAction(e -> {
            spinner.commitValue();
            settle.run();
        });
        spinner.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                settle.run(); // after the spinner's own focus-loss commit, which was registered first
            }
        });
        // An arrow or a programmatic value is a value in force: the mark goes with the text it replaces.
        factory.valueProperty().addListener((obs, was, now) -> {
            spinner.getEditor().pseudoClassStateChanged(Styles.STATE_DANGER, false);
            spinner.setTooltip(null);
        });
        return spinner;
    }

    /** Whether {@code text} is a whole number from {@code min} to {@code max} (surrounding blanks allowed). Pure. */
    static boolean accepts(String text, int min, int max) {
        String t = text == null ? "" : text.strip();
        if (!t.matches("[+-]?\\d{1,10}")) {
            return false;
        }
        long v = Long.parseLong(t);
        return v >= min && v <= max;
    }

    /**
     * The value typed text stands for: the number clamped to {@code min..max}, or {@code current} when the text
     * is empty or not a whole number. Digits beyond the {@code int} range still clamp rather than fail.
     */
    static int parse(String text, int current, int min, int max) {
        String t = text == null ? "" : text.strip();
        if (!t.matches("[+-]?\\d+")) {
            return current;
        }
        try {
            return (int) Math.clamp(Long.parseLong(t), (long) min, (long) max);
        } catch (NumberFormatException tooLong) {
            return t.startsWith("-") ? min : max;
        }
    }
}
