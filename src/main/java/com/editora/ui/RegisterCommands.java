package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.stage.Stage;

import com.editora.editops.Registers;
import com.editora.editor.EditorBuffer;
import com.editora.vfs.Vfs;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;
import org.reactfx.Subscription;

import static com.editora.i18n.Messages.tr;

/**
 * Emacs registers in a window: copy the region to a register ({@code C-x r s}; with {@code C-u}, cut it),
 * insert a register ({@code C-x r i}), save the caret position in one ({@code C-x r SPC}), jump to it
 * ({@code C-x r j}, opening the file when it is closed), and list them all. Each command asks for the
 * register's name — one character — and stands down on Escape or when the caret moves first.
 *
 * <p>A saved position follows the edits made to its file while the file is open in this window, as an Emacs
 * marker does; once the file is closed it keeps its last offset, and a jump clamps it to the file.
 */
final class RegisterCommands {

    interface Host {
        @Nullable
        EditorBuffer activeBuffer();

        void setStatus(String message);

        /** Whether the active buffer may be edited; says why not when it may not. */
        boolean activeEditable();

        /** The {@code C-u} argument the command was run with, or null. */
        @Nullable
        Integer prefixArgument();

        /** Opens {@code file} (or selects its tab) and runs {@code then} once its text is in. */
        void openThen(Path file, Runnable then);

        OverlayHost overlayHost();

        Stage stage();
    }

    private final Host host;
    private final Registers registers = new Registers();
    /** Buffers whose edits are moving position registers: subscribed once each. */
    private final Map<EditorBuffer, Subscription> tracked = new java.util.WeakHashMap<>();

    RegisterCommands(Host host) {
        this.host = host;
    }

    /** The store, for tests. */
    Registers registers() {
        return registers;
    }

    /** {@code copy-to-register}: the selection's text into a register; {@code C-u} deletes the selection too. */
    void copyToRegister() {
        EditorBuffer buffer = host.activeBuffer();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        if (area == null) {
            return;
        }
        if (area.getSelection().getLength() == 0) {
            host.setStatus(tr("status.register.noRegion"));
            return;
        }
        boolean cut = host.prefixArgument() != null;
        if (cut && !host.activeEditable()) {
            return;
        }
        int start = area.getSelection().getStart();
        int end = area.getSelection().getEnd();
        ask(area, "status.register.copyTo", name -> {
            registers.put(name, new Registers.Text(area.getText(start, end)));
            if (cut) {
                area.replaceText(start, end, "");
            } else {
                area.selectRange(area.getCaretPosition(), area.getCaretPosition()); // the region is done with
            }
            host.setStatus(tr("status.register.copied", String.valueOf(name)));
        });
    }

    /** {@code insert-register}: a text register's text at the caret, the caret staying before it (as Emacs). */
    void insertRegister() {
        EditorBuffer buffer = host.activeBuffer();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        if (area == null || !host.activeEditable()) {
            return;
        }
        ask(area, "status.register.insert", name -> {
            Registers.Register register = registers.get(name);
            if (!(register instanceof Registers.Text text)) {
                host.setStatus(tr(
                        register == null ? "status.register.empty" : "status.register.notText", String.valueOf(name)));
                return;
            }
            int at = area.getSelection().getStart();
            area.replaceSelection(text.text());
            area.moveTo(at);
            area.requestFollowCaret();
        });
    }

    /** {@code point-to-register}: the caret's position (file and offset) into a register. */
    void pointToRegister() {
        EditorBuffer buffer = host.activeBuffer();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        if (buffer == null || area == null) {
            return;
        }
        Path path = buffer.getPath();
        if (path == null) {
            host.setStatus(tr("status.register.untitled"));
            return;
        }
        int caret = area.getCaretPosition();
        ask(area, "status.register.point", name -> {
            String file = Vfs.toStorableString(path);
            registers.put(name, new Registers.Position(file, caret));
            track(buffer);
            host.setStatus(tr("status.register.pointSaved", String.valueOf(name)));
        });
    }

    /** {@code jump-to-register}: to a position register's place, opening its file when it is not open. */
    void jumpToRegister() {
        EditorBuffer buffer = host.activeBuffer();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        if (area == null) {
            return;
        }
        ask(area, "status.register.jump", name -> {
            Registers.Register register = registers.get(name);
            if (!(register instanceof Registers.Position position)) {
                host.setStatus(tr(
                        register == null ? "status.register.empty" : "status.register.notPosition",
                        String.valueOf(name)));
                return;
            }
            jump(position);
        });
    }

    /** {@code edit.viewRegisters}: every register in a picker; choosing one inserts it or jumps to it. */
    void viewRegisters() {
        List<Map.Entry<Character, Registers.Register>> all =
                List.copyOf(registers.all().entrySet());
        if (all.isEmpty()) {
            host.setStatus(tr("status.register.none"));
            return;
        }
        QuickOpen<Map.Entry<Character, Registers.Register>> picker = new QuickOpen<>(
                tr("command.edit.viewRegisters"),
                tr("palette.setting.pick"),
                () -> all,
                e -> e.getKey() + "  " + describe(e.getValue()),
                e -> "",
                e -> {
                    if (e == null) {
                        return;
                    }
                    if (e.getValue() instanceof Registers.Position position) {
                        jump(position);
                    } else if (e.getValue() instanceof Registers.Text text && host.activeEditable()) {
                        EditorBuffer buffer = host.activeBuffer();
                        if (buffer != null) {
                            buffer.getFocusedArea().replaceSelection(text.text());
                        }
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** One line about a register: the start of its text, or the file and line of its position. */
    static String describe(Registers.Register register) {
        if (register instanceof Registers.Text text) {
            String oneLine = text.text().replace('\n', '⏎').replace('\t', '→');
            return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
        }
        Registers.Position p = (Registers.Position) register;
        Path path = Vfs.parseStorable(p.file());
        return tr(
                "register.position",
                path == null ? p.file() : String.valueOf(path.getFileName()),
                String.valueOf(p.offset()));
    }

    private void jump(Registers.Position position) {
        Path path = Vfs.parseStorable(position.file());
        if (path == null) {
            host.setStatus(tr("status.register.fileGone", position.file()));
            return;
        }
        EditorBuffer active = host.activeBuffer();
        if (active != null && path.equals(active.getPath())) {
            moveTo(active, position.offset());
            return;
        }
        if (Vfs.isLocal(path) && !java.nio.file.Files.exists(path)) {
            host.setStatus(tr("status.register.fileGone", String.valueOf(path.getFileName())));
            return;
        }
        host.openThen(path, () -> {
            EditorBuffer opened = host.activeBuffer();
            if (opened != null && path.equals(opened.getPath())) {
                moveTo(opened, position.offset());
            }
        });
    }

    private static void moveTo(EditorBuffer buffer, int offset) {
        CodeArea area = buffer.getFocusedArea();
        area.moveTo(Math.clamp(offset, 0, area.getLength()));
        area.requestFollowCaret();
    }

    /** Keeps the position registers in {@code buffer}'s file in step with its edits. */
    private void track(EditorBuffer buffer) {
        if (tracked.containsKey(buffer)) {
            return;
        }
        tracked.put(buffer, buffer.getArea().plainTextChanges().subscribe(change -> {
            Path path = buffer.getPath();
            if (path != null) {
                registers.shift(
                        Vfs.toStorableString(path),
                        change.getPosition(),
                        change.getRemoved().length(),
                        change.getInserted().length());
            }
        }));
    }

    /** Prompts for a register name in the status line, then runs {@code then} with it. */
    private void ask(CodeArea area, String promptKey, java.util.function.Consumer<Character> then) {
        host.setStatus(tr(promptKey));
        ReadCharSession.read(area, () -> host.setStatus(""), then);
    }
}
