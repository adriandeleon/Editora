package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;

import com.editora.lsp.JavaRuntimes;
import org.jspecify.annotations.Nullable;

/** One installed JDK in a settings/run-configuration selector. Blank {@code home} is the inherited choice. */
record JdkChoice(String home, int major) {

    JdkChoice {
        home = home == null ? "" : home;
    }

    static List<JdkChoice> discover(String selectedHome) {
        com.editora.perf.BlockingSites.enter("JdkChoice.discover");
        Map<String, JdkChoice> byPath = new LinkedHashMap<>();
        List<JavaRuntimes.Jdk> jdks = JavaRuntimes.discover();
        if (com.editora.process.ProcessRunner.augmentedPathReady()) {
            // PATH's JDK, wherever it is installed, is one to choose too (review 3, A204); never waits for the shell
            jdks = JavaRuntimes.withPreferred(jdks, JavaRuntimes.pathJavaHome());
        }
        for (JavaRuntimes.Jdk jdk : jdks) {
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
            public @Nullable JdkChoice fromString(String value) {
                return null;
            }
        });
        combo.setMaxWidth(Double.MAX_VALUE);
    }

    /**
     * Fills {@code combo} with this machine's JDKs and selects {@code home}. The JDKs are looked for off the FX
     * thread (PERF-01, Pauta 6.0.1): until they land the list holds the inherited choice and {@code home} (its
     * version not known yet), selected; then the whole list, with the same home selected, now with its version. The
     * value changes then but its home does not: a listener that saves the home asks {@link #sameHome} first. A later
     * call's list wins.
     */
    static void select(ComboBox<JdkChoice> combo, String home) {
        String wanted = home == null ? "" : home.strip();
        long mine = nextLook(combo);
        combo.getItems().setAll(known(wanted));
        selectHome(combo, wanted);
        LOOKING.incrementAndGet();
        com.editora.perf.OffFx.then("JdkChoice.discover", () -> discover(wanted), found -> {
            LOOKING.decrementAndGet();
            if (mine != look(combo) || !found.ok()) {
                return; // a later fill is under way, or the look failed: the short list stays
            }
            JdkChoice current = combo.getValue();
            List<JdkChoice> all = new ArrayList<>(found.or(List.of()));
            // The selected home as the look found it (with its version), else kept as it was.
            JdkChoice selected = current == null
                    ? null
                    : all.stream()
                            .filter(c -> c.home().equals(current.home()))
                            .findFirst()
                            .orElse(current);
            if (selected != null && !all.contains(selected)) {
                all.add(selected);
            }
            combo.getItems().setAll(all);
            if (selected != null) {
                combo.getSelectionModel().select(selected);
            }
        });
    }

    /** Whether {@code a} and {@code b} name the same JDK home (a version learned later is no change of choice). */
    static boolean sameHome(@Nullable JdkChoice a, @Nullable JdkChoice b) {
        return (a == null ? "" : a.home()).equals(b == null ? "" : b.home());
    }

    /** What is known without a look: the inherited choice, and {@code selected} when one is. */
    static List<JdkChoice> known(String selected) {
        List<JdkChoice> choices = new ArrayList<>();
        choices.add(new JdkChoice("", 0));
        if (!selected.isEmpty()) {
            choices.add(new JdkChoice(selected, 0));
        }
        return List.copyOf(choices);
    }

    private static void selectHome(ComboBox<JdkChoice> combo, String wanted) {
        combo.getSelectionModel()
                .select(combo.getItems().stream()
                        .filter(c -> c.home().equals(wanted))
                        .findFirst()
                        .orElse(combo.getItems().get(0)));
    }

    private static final String LOOK = "jdk-choice-look";

    /** The {@link #select} looks not landed yet. */
    private static final java.util.concurrent.atomic.AtomicInteger LOOKING =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Whether a {@link #select} look has not landed yet: a test waits for the versions before it reads a combo. */
    static boolean looking() {
        return LOOKING.get() > 0;
    }

    private static long nextLook(ComboBox<JdkChoice> combo) {
        long next = look(combo) + 1;
        combo.getProperties().put(LOOK, next);
        return next;
    }

    private static long look(ComboBox<JdkChoice> combo) {
        return combo.getProperties().get(LOOK) instanceof Long l ? l : 0L;
    }

    /**
     * This machine's JDKs, looked for off the FX thread and handed to {@code then} on it (PERF-01, Pauta 6.0.1):
     * for a list that needs them all, as New Maven Project's Java versions do. An empty list when the look failed.
     */
    static void jdksLater(java.util.function.Consumer<List<JavaRuntimes.Jdk>> then) {
        com.editora.perf.OffFx.then(
                "JavaRuntimes.discover", JavaRuntimes::discover, found -> then.accept(found.or(List.of())));
    }
}
