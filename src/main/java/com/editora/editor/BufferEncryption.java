package com.editora.editor;

import javafx.scene.Node;
import javafx.scene.layout.BorderPane;

import org.jspecify.annotations.Nullable;

/**
 * A buffer's encryption: its {@link EncryptionState} (null for an ordinary file), the placeholder the tab shows
 * instead of the editor while the file is locked, and the banner about its encryption (a failed save). The
 * buffer owns one of these; the window's encryption feature fills it. FX thread only.
 *
 * <p>A buffer with a state is <em>sensitive</em> ({@link #isEncrypted}): every feature that would copy its
 * text somewhere — crash recovery, Local History, a language server, an agent — checks this first.
 */
public final class BufferEncryption {

    private final BorderPane outer;
    private final Node editor;
    private final Runnable editabilityChanged;
    private final Runnable noticeChanged;
    private @Nullable EncryptionState state;
    private @Nullable Node lockedView;
    private @Nullable Node notice;

    /**
     * @param outer              the buffer's outermost pane, whose centre is the editor
     * @param editor             the editor (its views), shown again when the file is unlocked
     * @param editabilityChanged re-applies the buffer's editability (a locked buffer is not editable)
     * @param noticeChanged      re-lays out the banners above the editor
     */
    BufferEncryption(BorderPane outer, Node editor, Runnable editabilityChanged, Runnable noticeChanged) {
        this.outer = outer;
        this.editor = editor;
        this.editabilityChanged = editabilityChanged;
        this.noticeChanged = noticeChanged;
    }

    /** The file's encryption, or null for an ordinary file. */
    public @Nullable EncryptionState state() {
        return state;
    }

    /**
     * Whether the buffer holds an encrypted file's text (or will, once it is unlocked): content that must not be
     * copied to disk or sent anywhere unless the user asks.
     */
    public boolean isEncrypted() {
        return state != null;
    }

    /** Marks the buffer as an encrypted file's, or with null an ordinary one (a kept passphrase is wiped). */
    public void setState(@Nullable EncryptionState next) {
        if (state != null && state != next) {
            state.wipe();
        }
        state = next;
    }

    /**
     * Shows {@code view} in place of the editor while the file is locked (its text is not here), or the editor
     * again with null. A locked buffer is not editable, so nothing can be typed into its empty document.
     */
    public void setLockedView(@Nullable Node view) {
        lockedView = view;
        outer.setCenter(view != null ? view : editor);
        editabilityChanged.run();
    }

    public boolean isLocked() {
        return lockedView != null;
    }

    public @Nullable Node lockedView() {
        return lockedView;
    }

    /** Shows (or with null removes) the encryption banner above the editor. */
    public void setNotice(@Nullable Node banner) {
        notice = banner;
        noticeChanged.run();
    }

    /** The encryption banner while one is shown, else null. */
    public @Nullable Node notice() {
        return notice;
    }

    /** Forgets a kept passphrase (the tab is closing). */
    void wipe() {
        if (state != null) {
            state.wipe();
        }
    }
}
