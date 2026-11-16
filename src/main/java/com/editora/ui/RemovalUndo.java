package com.editora.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The window's last small removals, taken back one at a time by Edit: Undo Last Removal ({@value #COMMAND}): a watch,
 * a breakpoint that carried a condition, a key binding, a dictionary word. They are removed with no question, because
 * a question on each would be tiresome, so each records here how to put it back (CONF-01, Pauta 6.0.1), and the
 * status bar says so.
 *
 * <p>One per editor window, held by its {@link MainController} and emptied when the window closes: an entry's
 * restore captures the buffer, the panel or the page it removed from, and a list that outlived the window would keep
 * all of it alive. Never static for the same reason. It keeps the {@value #LIMIT} newest removals.
 *
 * <p>A restore answers whether it put the thing back. It restores through the model or store the removal went
 * through, and answers false, changing nothing, when its place is gone (the file was closed, the session's panel is
 * gone) or taken by something made since (another breakpoint on the line, another binding on the key). Either way the
 * entry is used up: there is no redo.
 */
final class RemovalUndo {

    /** The command that takes the newest removal back. */
    static final String COMMAND = "edit.undoRemoval";

    /** How many removals are kept; the oldest falls off. */
    static final int LIMIT = 20;

    private record Entry(String removed, String restored, BooleanSupplier restore) {}

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Consumer<@Nullable String> status;

    /** {@code status} is the window's status bar. */
    RemovalUndo(Consumer<@Nullable String> status) {
        this.status = status;
    }

    /**
     * Records a removal that just happened and says so: {@code removedSentence} ("Watch “total” removed."), then that
     * Undo Last Removal brings it back. {@code restore} puts it back and answers whether it did (see the class
     * comment); {@code restoredSentence} is what the status bar says when it did.
     */
    void removed(String removedSentence, String restoredSentence, BooleanSupplier restore) {
        entries.push(new Entry(removedSentence, restoredSentence, restore));
        while (entries.size() > LIMIT) {
            entries.removeLast();
        }
        status.accept(removedSentence + " " + hint());
    }

    /** Edit: Undo Last Removal — puts the newest removal back, or says why not. */
    void undoLast() {
        Entry entry = entries.poll();
        if (entry == null) {
            status.accept(tr("status.removal.nothing"));
            return;
        }
        boolean restored;
        try {
            restored = entry.restore().getAsBoolean();
        } catch (RuntimeException e) {
            restored = false; // a restore answers false rather than throw; one that threw all the same is not retried
        }
        status.accept(restored ? entry.restored() : tr("status.removal.cannotUndo", entry.removed()));
    }

    /** Forgets every removal: the window is closing. */
    void clear() {
        entries.clear();
    }

    /** How many removals can be taken back. */
    int size() {
        return entries.size();
    }

    /** "Undo Last Removal (C-c z) brings it back.": the command by its title and, when it has one, its key. */
    static String hint() {
        return tr("status.removal.hint", CommandTips.text(CommandTips.title(COMMAND), COMMAND));
    }
}
