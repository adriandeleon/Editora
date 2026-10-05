package com.editora.ui;

import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.util.StringConverter;

/**
 * Editable whole-number spinners that cannot be typed into a broken state.
 *
 * <p>A stock editable {@code Spinner<Integer>} converts its text with {@code IntegerStringConverter}: letters
 * (or a number too large for an {@code int}) throw out of the commit, and an empty field commits {@code null}
 * — after which the arrow buttons throw until the value is set again. Here text that is not a number keeps the
 * current value, a number outside the range is pulled to the nearest bound, and the field is rewritten to show
 * the value that is actually in force.
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
                return parse(text, current == null ? initial : current, min, max);
            }
        });
        spinner.setEditable(true);
        // A commit that leaves the value unchanged ("abc", or 99 where 16 is already the maximum) fires no
        // value change, so the field would keep showing the rejected text. Rewrite it after each commit.
        Runnable showValue =
                () -> spinner.getEditor().setText(factory.getConverter().toString(factory.getValue()));
        spinner.getEditor().setOnAction(e -> {
            spinner.commitValue();
            showValue.run();
        });
        spinner.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                showValue.run(); // after the spinner's own focus-loss commit, which was registered first
            }
        });
        return spinner;
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
