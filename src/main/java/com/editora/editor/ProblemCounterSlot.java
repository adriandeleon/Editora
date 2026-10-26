package com.editora.editor;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.Node;

import com.editora.text.LspDiagnostic;
import org.jspecify.annotations.Nullable;

/**
 * Where a buffer's {@link ProblemCounter} comes and goes: mounted in the editor's top-right row while a language
 * server checks the file and the setting is on, and fed the file's diagnostics. Built on first need.
 */
public final class ProblemCounterSlot {

    private final BooleanSupplier possible;
    private final Consumer<Node> mount;
    private final Consumer<Node> unmount;
    private @Nullable ProblemCounter counter;
    private boolean enabled = true;
    private boolean mounted;
    private Runnable open = () -> {};
    private Runnable previous = () -> {};
    private Runnable next = () -> {};
    private List<LspDiagnostic> diagnostics = List.of();
    /** Whether the server has published this file's diagnostics since it was (re)opened on it. */
    private boolean checked;
    /** Why the file is not checked although a server has it (its project did not import), or null. */
    private @Nullable String note;

    private java.util.function.Function<String, @Nullable String> chords = id -> null;

    /** {@code possible}: a server checks the file; {@code mount}/{@code unmount} add it to and take it from the row. */
    ProblemCounterSlot(BooleanSupplier possible, Consumer<Node> mount, Consumer<Node> unmount) {
        this.possible = possible;
        this.mount = mount;
        this.unmount = unmount;
    }

    /** Whether the counter shows (Settings ▸ Language server features, on by default). */
    public void setEnabled(boolean on) {
        enabled = on;
        update();
    }

    /** What its clicks do: open Problems, and go to the previous and next problem. */
    public void setActions(Runnable open, Runnable previous, Runnable next) {
        this.open = open == null ? () -> {} : open;
        this.previous = previous == null ? () -> {} : previous;
        this.next = next == null ? () -> {} : next;
    }

    /** The server published the file's diagnostics. */
    void show(List<LspDiagnostic> latest) {
        diagnostics = List.copyOf(latest);
        checked = true;
        update();
    }

    /** The file is (re)opened on a server, or left one: nothing is known until it publishes again. */
    void reset() {
        diagnostics = List.of();
        checked = false;
        update();
    }

    /** Why the file is not checked although a server has it (its project did not import), or null to clear it. */
    public void setNote(@Nullable String reason) {
        if (!java.util.Objects.equals(note, reason)) {
            note = reason;
            update();
        }
    }

    /** The active keymap's chords for command ids, for the arrows' tooltips. */
    public void setChords(java.util.function.Function<String, @Nullable String> chords) {
        this.chords = chords == null ? id -> null : chords;
        if (counter != null) {
            counter.setChords(this.chords);
        }
    }

    /** Mounts or unmounts it for the buffer's state now. */
    void update() {
        if (!enabled || !possible.getAsBoolean()) {
            if (mounted && counter != null) {
                unmount.accept(counter);
            }
            mounted = false;
            return;
        }
        if (counter == null) {
            counter = new ProblemCounter(() -> open.run(), () -> previous.run(), () -> next.run());
            counter.setChords(chords);
        }
        String noteNow = note;
        counter.show(
                diagnostics,
                noteNow != null
                        ? noteNow
                        : checked ? null : com.editora.i18n.Messages.tr("editor.problemCounter.analyzing"));
        mount.accept(counter);
        mounted = true;
    }

    /** The counter while it shows, else null (tests). */
    public @Nullable Node counter() {
        return mounted ? counter : null;
    }
}
