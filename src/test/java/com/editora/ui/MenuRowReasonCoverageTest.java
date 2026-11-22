package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A right-click or drop-down row that cannot run says why (KBD-04, Pauta 6.2.0): it is grayed through {@code
 * RowReasons} ({@code because}, {@code when}, {@code featureOn}, {@code bind}, {@code set}), which takes the sentence,
 * or made a {@code RowReasons.placeholder} when it is a label rather than a command. Read from the source: a call of
 * {@code setDisable(…)} or {@code disableProperty()} on a variable whose declaration in the same file is a {@code
 * MenuItem}, {@code CheckMenuItem}, {@code RadioMenuItem}, {@code Menu} or {@code CustomMenuItem} (or on a method that
 * returns an {@code …Item()}) fails, unless its file is counted here with the reason.
 *
 * <p>What it cannot see: a row grayed through a variable declared in another file or typed as something wider (a
 * {@code Node}, a generic {@code T}), a row grayed by a helper of another class that takes it as an argument, a menu
 * JavaFX builds itself (a text field's own menu, which {@code RowReasons} explains as it shows), and whether the
 * sentence a row says is the right one (each menu's tests say that). A file with fewer such calls than it is allowed
 * fails too: lower the count.
 */
@Tag("guard")
class MenuRowReasonCoverageTest {

    /** {@code File}: how many rows it may gray without a reason, and why. */
    private static final Map<String, Allowed> ALLOWED = Map.ofEntries(allow(
            "MainMenuBar",
            2,
            "the menu bar's own rows say why through MainMenuBar.showAvailability; on the macOS system bar a row can"
                    + " only be disabled"));

    private record Allowed(int count, String reason) {}

    private static Map.Entry<String, Allowed> allow(String file, int count, String reason) {
        return Map.entry(file, new Allowed(count, reason));
    }

    /** A call that grays something: {@code name.setDisable(} or {@code name.disableProperty()}. */
    private static final Pattern GRAYS =
            Pattern.compile("(\\w+)(\\s*\\(\\s*\\))?\\s*\\.\\s*(?:setDisable\\s*\\(|disableProperty\\s*\\(\\s*\\))");

    private static final String MENU_TYPE =
            "(?:javafx\\.scene\\.control\\.)?(?:MenuItem|CheckMenuItem|RadioMenuItem|Menu|CustomMenuItem)";

    @Test
    void everyGrayedMenuRowSaysWhyOrIsCountedHereWithAReason() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                int silent = silentlyGrayedRows(Files.readString(file));
                if (silent > 0) {
                    found.put(file.getFileName().toString().replace(".java", ""), silent);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        found.forEach((file, count) -> {
            Allowed allowed = ALLOWED.get(file);
            if (allowed == null || count > allowed.count()) {
                problems.add(file + ": " + count + " menu rows grayed with setDisable or disableProperty (allowed "
                        + (allowed == null ? 0 : allowed.count())
                        + "). Gray a row that cannot run with RowReasons.because / when / featureOn, which takes the "
                        + "sentence that says why, and make a row that is a label a RowReasons.placeholder.");
            }
        });
        ALLOWED.forEach((file, allowed) -> {
            int count = found.getOrDefault(file, 0);
            if (count < allowed.count()) {
                problems.add(
                        file + ": allowed " + allowed.count() + " silently grayed rows, now " + count + ": lower it");
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theScanTellsARowGrayedSilentlyFromOneThatSaysWhy() {
        assertEquals(1, silentlyGrayedRows("MenuItem cut = new MenuItem(\"x\");\ncut.setDisable(true);"));
        assertEquals(1, silentlyGrayedRows("Menu git = new Menu(\"x\");\ngit.disableProperty().bind(off);"));
        assertEquals(
                1,
                silentlyGrayedRows(
                        "javafx.scene.control.MenuItem endInput = new javafx.scene.control.MenuItem(\"x\");\n"
                                + "endInput.setDisable(true);"));
        assertEquals(1, silentlyGrayedRows("m.copyItem().setDisable(!hasSelection());"));
        assertEquals(1, silentlyGrayedRows("for (MenuItem row : rows) {\n    row.setDisable(empty);\n}"));
        assertEquals(0, silentlyGrayedRows("MenuItem cut = new MenuItem(\"x\");\nRowReasons.when(cut, ok, \"k\");"));
        assertEquals(0, silentlyGrayedRows("Button cut = new Button(\"x\");\ncut.setDisable(true);"));
        // The nearest declaration before the call is the one that counts: a button of the same name elsewhere.
        assertEquals(
                0,
                silentlyGrayedRows("void a() { MenuItem pause = new MenuItem(); }\n"
                        + "void b() { Button pause = new Button(); pause.setDisable(true); }"));
    }

    /** How many calls in {@code source} gray a menu row without going through {@code RowReasons}. */
    static int silentlyGrayedRows(String source) {
        int silent = 0;
        Matcher m = GRAYS.matcher(source);
        while (m.find()) {
            String name = m.group(1);
            boolean call = m.group(2) != null;
            if (call ? name.endsWith("Item") : isMenuRow(source, name, m.start())) {
                silent++;
            }
        }
        return silent;
    }

    /**
     * Whether {@code name} at {@code at} is a menu row's: its declaration nearest before {@code at} in the same member
     * (no class-level closing brace between them), else its field's declaration.
     */
    private static boolean isMenuRow(String source, String name, int at) {
        Matcher decl = Pattern.compile("([\\w.]+)(?:<[^>]*>)?\\s+" + Pattern.quote(name) + "\\s*[=;,):]")
                .matcher(source);
        String local = null;
        String field = null;
        while (decl.find()) {
            String declared = decl.group(1);
            if (declared.equals("return") || declared.equals("new") || declared.equals("else")) {
                continue;
            }
            int lineStart = source.lastIndexOf('\n', decl.start()) + 1;
            String before = source.substring(lineStart, decl.start());
            if (before.contains("//") || before.trim().startsWith("*")) {
                continue; // words in a comment ("no command to copy: …") declare nothing
            }
            String indent = before.replaceAll("\\S.*", "");
            boolean isField = indent.equals("    ") && !before.contains("(");
            if (isField && field == null) {
                field = typeOf(source, name, declared);
            } else if (!isField
                    && decl.start() < at
                    && !source.substring(decl.start(), at).contains("\n    }\n")) {
                local = typeOf(source, name, declared);
            }
        }
        String type = local != null ? local : field;
        return type != null && type.matches(MENU_TYPE);
    }

    /** {@code declared}, or for a {@code var} the class it is assigned a new instance of. */
    private static String typeOf(String source, String name, String declared) {
        if (!declared.equals("var")) {
            return declared;
        }
        Matcher assigned = Pattern.compile("var\\s+" + Pattern.quote(name) + "\\s*=\\s*new\\s+([\\w.]+)")
                .matcher(source);
        return assigned.find() ? assigned.group(1) : declared;
    }
}
