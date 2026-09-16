package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;

import com.editora.lsp.JavaRuntimes;

/** One installed JDK in a settings/run-configuration selector. Blank {@code home} is the inherited choice. */
record JdkChoice(String home, int major) {

    JdkChoice {
        home = home == null ? "" : home;
    }

    static List<JdkChoice> discover(String selectedHome) {
        Map<String, JdkChoice> byPath = new LinkedHashMap<>();
        for (JavaRuntimes.Jdk jdk : JavaRuntimes.discover()) {
            byPath.putIfAbsent(jdk.path(), new JdkChoice(jdk.path(), jdk.major()));
        }
        String selected = selectedHome == null ? "" : selectedHome.strip();
        if (!selected.isEmpty()) {
            byPath.putIfAbsent(selected, new JdkChoice(selected, 0));
        }
        List<JdkChoice> choices = new ArrayList<>();
        choices.add(new JdkChoice("", 0));
        choices.addAll(byPath.values());
        return List.copyOf(choices);
    }

    static void configure(ComboBox<JdkChoice> combo, String automaticLabel) {
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(JdkChoice choice) {
                if (choice == null || choice.home().isBlank()) {
                    return automaticLabel;
                }
                String prefix = choice.major() > 0 ? "JDK " + choice.major() : "JDK";
                return prefix + " — " + choice.home();
            }

            @Override
            public JdkChoice fromString(String value) {
                return null;
            }
        });
        combo.setMaxWidth(Double.MAX_VALUE);
    }

    static void select(ComboBox<JdkChoice> combo, String home) {
        String wanted = home == null ? "" : home.strip();
        combo.getItems().setAll(discover(wanted));
        combo.getSelectionModel()
                .select(combo.getItems().stream()
                        .filter(c -> c.home().equals(wanted))
                        .findFirst()
                        .orElse(combo.getItems().get(0)));
    }
}
