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
 * A dialog that is answered with a button is shown through {@link Dialogs#showAndWait}, or through
 * {@link Dialogs#show(javafx.scene.control.Dialog, java.util.function.Consumer)} when it must not block, which a
 * test answers with no window (CONF-12, Pauta 6.2.0), never through its own {@code showAndWait()} or {@code show()}.
 * Read from the source, under {@code src/main/java}: a call of {@code showAndWait()} with no argument, a reference
 * {@code X::showAndWait} to anything but {@code Dialogs}, and a call of {@code show()} on a variable whose
 * declaration in the same file is an {@code Alert}, a {@code Dialog}, a {@code TextInputDialog} or a
 * {@code ChoiceDialog} (or a {@code var} assigned a new one) fail, unless the file is counted here with the reason.
 * What is counted are the form dialogs (fields and a result of their own, a text prompt), which are not questions,
 * and the two real calls inside {@code Dialogs} itself.
 *
 * <p>What it cannot see: a dialog shown with {@code show()} through a variable declared in another file or typed
 * as something wider, through a name reached with a dot ({@code this.alert.show()}, {@code prompt.alert().show()}),
 * straight from the call that built it ({@code failureAlert(…).show()}), or by a class of the
 * app's own that extends {@code Dialog} (there is none today); {@code show()} on a {@code Stage}, a popup or a
 * menu is everywhere and is not a dialog, so only the four dialog types are looked for. Nor a call that reaches
 * {@code showAndWait()} through a helper of another name or through reflection, a window the app builds from a
 * {@code Stage} and a native file chooser, which are not dialogs with buttons to answer (a {@code Stage}'s own
 * {@code showAndWait()} would be counted, and needs an entry here), and whether a question's words come from a
 * pure function ({@code ConfirmWordsTest} calls each one that does). A file with fewer such calls than it is
 * allowed fails too: lower the count.
 */
@Tag("guard")
class DialogShownThroughHookTest {

    private static final String FORM = "a form with fields and a result of its own, not a question";

    /** {@code File}: how many dialogs it may show by itself, and why. */
    private static final Map<String, Allowed> ALLOWED = Map.ofEntries(
            allow("Dialogs", 2, "the two hooked methods themselves: the one place a dialog is really shown"),
            allow("ConfigImportFlow", 1, FORM + ": which parts of a configuration zip to import"),
            allow("ExportConfigDialog", 1, FORM + ": what to put in a configuration export"),
            allow("ExceptionBreakpointDialog", 1, FORM + ": an exception breakpoint's type and condition"),
            allow("JavaCleanupsDialog", 1, FORM + ": the list of Java clean-ups to tick"),
            allow("MacroSettingsPane", 1, FORM + ": the command picker of a macro step, a filter and a list"),
            allow("RemoteCoordinator", 1, FORM + ": the password or passphrase prompt of a remote login"),
            allow("SettingsSyncPage", 1, "a text prompt: the name of the repository to create"),
            allow("StepFilterDialog", 1, FORM + ": a step filter's pattern"),
            allow("TypeRendererDialog", 1, FORM + ": a debugger type renderer"));

    private record Allowed(int count, String reason) {}

    private static Map.Entry<String, Allowed> allow(String file, int count, String reason) {
        return Map.entry(file, new Allowed(count, reason));
    }

    /** A dialog shown by itself: {@code .showAndWait()} with no argument, or {@code X::showAndWait}. */
    private static final Pattern SHOWN =
            Pattern.compile("\\.\\s*showAndWait\\s*\\(\\s*\\)|(\\w+)\\s*::\\s*showAndWait\\b");

    @Test
    void everyDialogIsShownThroughTheHookedMethodOrIsCountedHereWithAReason() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file);
                int shown = shownByItself(source) + shownWithoutWaiting(source);
                if (shown > 0) {
                    found.merge(file.getFileName().toString().replace(".java", ""), shown, Integer::sum);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        found.forEach((file, count) -> {
            Allowed allowed = ALLOWED.get(file);
            if (allowed == null || count > allowed.count()) {
                problems.add(file + ": " + count + " dialogs shown with their own showAndWait() or show() (allowed "
                        + (allowed == null ? 0 : allowed.count())
                        + "). Show a dialog that is answered with a button through Dialogs.showAndWait(dialog), or"
                        + " Dialogs.show(dialog, onResult) where it must not block, so a test answers it with no"
                        + " window; a question before a destructive action is asked through Confirm.ask, or builds"
                        + " its words in a static function.");
            }
        });
        ALLOWED.forEach((file, allowed) -> {
            int count = found.getOrDefault(file, 0);
            if (count < allowed.count()) {
                problems.add(file + ": allowed " + allowed.count() + " dialogs shown by themselves, now " + count
                        + ": lower it");
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theScanTellsADialogShownByItselfFromOneShownThroughTheHook() {
        assertEquals(1, shownByItself("Optional<ButtonType> r = alert.showAndWait();"));
        assertEquals(1, shownByItself("return build(q)\n        .showAndWait();"));
        assertEquals(1, shownByItself("prompt.alert().showAndWait().orElse(cancel)"));
        assertEquals(1, shownByItself("Function<Alert, Optional<ButtonType>> ask = Alert::showAndWait;"));
        assertEquals(2, shownByItself("a.showAndWait();\nb.showAndWait ( );"));
        assertEquals(0, shownByItself("Optional<ButtonType> r = Dialogs.showAndWait(alert);"));
        assertEquals(0, shownByItself("Function<Alert, Optional<ButtonType>> ask = Dialogs::showAndWait;"));
        assertEquals(0, shownByItself("alert.show(); // not showAndWait: the windows are still being restored"));
        assertEquals(0, shownByItself("    // it used to call alert.showAndWait() here"));
        assertEquals(0, shownByItself("     * what {@code dialog.showAndWait()} returns"));
        assertEquals(1, shownByItself("alert.showAndWait(); // waits"));
    }

    @Test
    void theScanTellsADialogShownWithoutWaitingFromAStageOrAPopup() {
        assertEquals(1, shownWithoutWaiting("Alert alert = Dialogs.styled(new Alert(type));\nalert.show();"));
        assertEquals(1, shownWithoutWaiting("void ask(Dialog<String> prompt) {\n    prompt.show();\n}"));
        assertEquals(
                1,
                shownWithoutWaiting("javafx.scene.control.TextInputDialog dialog =\n"
                        + "        Dialogs.styled(new javafx.scene.control.TextInputDialog(\"\"));\ndialog.show();"));
        assertEquals(1, shownWithoutWaiting("var note = Dialogs.styled(new Alert(type));\nnote.show();"));
        assertEquals(1, shownWithoutWaiting("private final ChoiceDialog<String> pick;\nvoid a() { pick.show(); }"));
        assertEquals(0, shownWithoutWaiting("Alert alert = Dialogs.styled(new Alert(type));\nDialogs.show(alert);"));
        assertEquals(0, shownWithoutWaiting("Stage stage = new Stage();\nstage.show();"));
        assertEquals(0, shownWithoutWaiting("var stage = new Stage();\nstage.show();"));
        assertEquals(0, shownWithoutWaiting("ContextMenu menu = new ContextMenu();\nmenu.show();"));
        assertEquals(0, shownWithoutWaiting("Alert alert = build();\n// alert.show() used to be here"));
        // The nearest declaration before the call is the one that counts: a stage of the same name elsewhere.
        assertEquals(
                0,
                shownWithoutWaiting("void a() { Alert s = build(); Dialogs.show(s); }\n"
                        + "void b() { Stage s = new Stage(); s.show(); }"));
    }

    /** A call of {@code show()} with no argument on a name: {@code name.show()}. */
    private static final Pattern SHOW = Pattern.compile("(?<![\\w.)])(\\w+)\\s*\\.\\s*show\\s*\\(\\s*\\)");

    private static final String DIALOG_TYPE =
            "(?:javafx\\.scene\\.control\\.)?(?:Alert|TextInputDialog|ChoiceDialog|Dialog)";

    /**
     * How many times {@code source} calls {@code show()} on a variable it declares as a dialog, outside its
     * comments.
     */
    static int shownWithoutWaiting(String source) {
        int shown = 0;
        Matcher m = SHOW.matcher(source);
        while (m.find()) {
            if (inComment(source, m.start())) {
                continue;
            }
            String type = declaredType(source, m.group(1), m.start());
            if (type != null && type.matches(DIALOG_TYPE)) {
                shown++;
            }
        }
        return shown;
    }

    /**
     * The type {@code name} is declared with: its declaration nearest before {@code at}, else its first one in the
     * file (a field declared below its use); for a {@code var}, the class it is assigned a new instance of.
     */
    private static String declaredType(String source, String name, int at) {
        Matcher decl = Pattern.compile("([\\w.]+)(?:<[^;(){}]*>)?\\s+" + Pattern.quote(name) + "\\s*[=;,):]")
                .matcher(source);
        String before = null;
        String anywhere = null;
        while (decl.find()) {
            String declared = decl.group(1);
            if (declared.equals("return")
                    || declared.equals("new")
                    || declared.equals("else")
                    || inComment(source, decl.start())) {
                continue;
            }
            if (declared.equals("var")) {
                int end = source.indexOf(';', decl.end());
                Matcher made = Pattern.compile("new\\s+(" + DIALOG_TYPE + ")\\b")
                        .matcher(source.substring(decl.end(), end < 0 ? source.length() : end));
                declared = made.find() ? made.group(1) : declared;
            }
            if (decl.start() < at) {
                before = declared;
            } else if (anywhere == null) {
                anywhere = declared;
            }
        }
        return before != null ? before : anywhere;
    }

    private static boolean inComment(String source, int at) {
        int lineStart = source.lastIndexOf('\n', at) + 1;
        String before = source.substring(lineStart, at);
        String trimmed = before.stripLeading();
        return before.contains("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    /** How many times {@code source} shows a dialog by itself, outside its comments. */
    static int shownByItself(String source) {
        int shown = 0;
        Matcher m = SHOWN.matcher(source);
        while (m.find()) {
            if ("Dialogs".equals(m.group(1))) {
                continue; // a reference to the hooked method
            }
            if (inComment(source, m.start())) {
                continue; // words in a comment show nothing
            }
            shown++;
        }
        return shown;
    }
}
