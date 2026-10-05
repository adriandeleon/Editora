package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Parent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;

/**
 * Shared driving code for the editing-view FX tests: a shown, sized window, files opened through the real
 * open path, and <em>real key events</em> fired at the editor so a chord travels through the scene-level
 * {@code KeyDispatcher} exactly as a key press does (which is the path several of these defects live on —
 * running the command through the registry alone would not reproduce them).
 */
final class EditingFx {

    final FxWindowFixture fx;
    final CommandRegistry registry;

    private EditingFx(FxWindowFixture fx) {
        this.fx = fx;
        this.registry = FxTestSupport.field(fx.controller, "registry");
    }

    static EditingFx create() throws Exception {
        FxTestSupport.bootToolkit();
        EditingFx e = new EditingFx(FxWindowFixture.create());
        FxTestSupport.runOnFx(() -> {
            Stage stage = e.stage();
            stage.setWidth(1100);
            stage.setHeight(700);
            if (!stage.isShowing()) {
                stage.show();
            }
        });
        e.pulses(20);
        return e;
    }

    Stage stage() {
        Parent root = FxTestSupport.field(fx.controller, "root");
        return (Stage) root.getScene().getWindow();
    }

    void dispose() throws Exception {
        fx.dispose();
    }

    /** Lets queued FX work and a few layout pulses run. */
    void pulses(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            FxTestSupport.drainFx();
            Thread.sleep(10);
        }
        FxTestSupport.drainFx();
    }

    /** Polls {@code condition} on the FX thread until it holds (or ~5 s pass); returns whether it held. */
    boolean await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 250; i++) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    EditorBuffer active() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    /** Writes {@code content} to a temp file and opens it through {@code openAndNavigate}, waiting for the load. */
    EditorBuffer open(String name, String content) throws Exception {
        Path dir = Files.createTempDirectory("editora-editing-fx");
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return select(file);
    }

    /** Opens (or re-selects) {@code file} and waits until it is the loaded, active buffer. */
    EditorBuffer select(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        EditorBuffer[] found = new EditorBuffer[1];
        boolean ok = await(() -> {
            EditorBuffer b = (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
            if (b != null && file.equals(b.getPath()) && !b.isLoading()) {
                found[0] = b;
                return true;
            }
            return false;
        });
        if (!ok) {
            throw new IllegalStateException("did not open " + file);
        }
        pulses(20);
        FxTestSupport.runOnFx(() -> found[0].getFocusedArea().requestFocus());
        pulses(5);
        return found[0];
    }

    /** Switches to {@code file}'s already-open tab the way a tab click does (no navigation). */
    void selectTab(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Object tab = FxTestSupport.call(fx.controller, "tabForPath", new Class<?>[] {Path.class}, file);
            FxTestSupport.call(
                    fx.controller, "activateAndFocusTab", new Class<?>[] {javafx.scene.control.Tab.class}, tab);
        });
        pulses(10);
    }

    void run(String commandId) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(commandId));
        pulses(10);
    }

    /** A control chord ({@code C-y}, {@code C-n}, {@code C-SPC}) pressed in {@code area}. */
    void ctrl(CodeArea area, KeyCode code) throws Exception {
        key(area, code, null, true, false);
    }

    /** A meta chord ({@code M-y}). */
    void meta(CodeArea area, KeyCode code) throws Exception {
        key(area, code, null, false, true);
    }

    /** A plain printable key: press, the character, release. */
    void type(CodeArea area, KeyCode code, String ch) throws Exception {
        key(area, code, ch, false, false);
    }

    private void key(CodeArea area, KeyCode code, String ch, boolean ctrl, boolean alt) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, alt, false));
            if (ch != null) {
                Event.fireEvent(
                        area, new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, ctrl, alt, false));
            }
            Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, ctrl, alt, false));
        });
        pulses(6);
    }

    /** Whether the caret's paragraph is inside the laid-out viewport of {@code area}. */
    boolean caretVisible(CodeArea area) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            int par = area.getCurrentParagraph();
            return area.firstVisibleParToAllParIndex() <= par && par <= area.lastVisibleParToAllParIndex();
        });
    }

    String viewport(CodeArea area) throws Exception {
        return FxTestSupport.callOnFx(() -> "caret line " + area.getCurrentParagraph() + ", visible "
                + area.firstVisibleParToAllParIndex() + ".." + area.lastVisibleParToAllParIndex());
    }

    static String lines(String prefix, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append(prefix).append(' ').append(i).append('\n');
        }
        return sb.toString();
    }
}
