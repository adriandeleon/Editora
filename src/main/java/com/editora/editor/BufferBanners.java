package com.editora.editor;

import java.nio.file.Files;

import javafx.scene.control.Button;
import javafx.scene.control.Label;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The bars stacked above a buffer's text: the encryption notice, a notice about the file, the language-support
 * install banner, the View Mode banner (with Enable Editing, or Edit as Administrator for a file not writable on
 * disk), the narrowing banner and the log control.
 */
final class BufferBanners {

    private final EditorBuffer b;

    BufferBanners(EditorBuffer b) {
        this.b = b;
        this.onEnableEditing = () -> b.setViewMode(false);
    }

    /** The MS-Word-style read-only banner (lazy); its "Enable Editing" runs {@link #onEnableEditing}. */
    private @Nullable ViewModeBar viewModeBar;

    private boolean viewModeBarVisible;
    /** Why this buffer is in View mode when it is not the general reason; cleared when editing is enabled. */
    @Nullable
    String viewModeReason;
    /** When true, a non-writable-on-disk file offers "Edit as Administrator" instead of a dead-end note. */
    private boolean adminEditAvailable;

    @Nullable
    Boolean writableOnDisk; // null: ask Files.isWritable; else what the window found out (remote files)

    /** IntelliJ-style "install language support?" banner (lazy), stacked above the view-mode bar; driven by
     *  MainController via {@link #setInstallPrompt}/{@link #showInstallBar}. */
    private @Nullable NoticeBar installBar;

    private boolean installBarShown;
    /** A notice about the file itself (a shell script's CRLF/BOM), above the install banner; null until used. */
    private @Nullable NoticeBar fileNotice;

    private boolean fileNoticeShown;
    /** Invoked by the banner's "Enable Editing" button; the controller persists + refreshes indicators. */
    private Runnable onEnableEditing;
    /** Lets the controller wire the banner's "Enable Editing" action (persist + refresh indicators). */
    void setOnEnableEditing(Runnable onEnableEditing) {
        this.onEnableEditing = onEnableEditing == null ? () -> b.setViewMode(false) : onEnableEditing;
    }

    /**
     * When true, a file that isn't writable on disk shows "Edit as Administrator" in the View-mode banner
     * (Save then routes through an elevated write) rather than a dead-end "read-only on disk" note. The
     * controller pushes this = admin-save enabled + the OS elevation tool is available + the file is local.
     */
    void setAdminEditAvailable(boolean available) {
        if (available != adminEditAvailable) {
            adminEditAvailable = available;
            updateViewModeBar();
        }
    }

    /**
     * Whether the file's permissions allow writing it, from the layer that can tell: {@code Files.isWritable}
     * answers yes for any existing SFTP file. {@code null} (the default) asks the file system directly.
     */
    void setWritableOnDisk(@Nullable Boolean writable) {
        writableOnDisk = writable;
        updateViewModeBar();
    }

    /**
     * Shows/hides the MS-Word-style "View Mode" banner above the editor: visible only in user View mode
     * (not huge-file mode, which can't be made editable). The "Enable Editing" button appears only when
     * the file is writable; otherwise a "read-only on disk" note replaces it.
     */
    void updateViewModeBar() {
        boolean show = b.viewMode && !b.hugeFile;
        if (show) {
            ViewModeBar bar = viewModeBar != null ? viewModeBar : new ViewModeBar(() -> onEnableEditing.run());
            viewModeBar = bar;
            bar.setReason(viewModeReason);
            Button editButton = bar.enableEditing();
            Label note = bar.note();
            boolean canEdit = b.path == null || (writableOnDisk != null ? writableOnDisk : Files.isWritable(b.path));
            // A non-writable file offers "Edit as Administrator" when elevation is available (Linux/pkexec),
            // instead of a dead-end "read-only on disk" note. Enabling editing routes the eventual Save
            // through the elevated write.
            boolean adminOffer = !canEdit && adminEditAvailable;
            editButton.setText(tr(adminOffer ? "viewmode.editAsAdmin" : "viewmode.enableEditing"));
            // A filtered log is a read-only subset whatever the mode: offering to edit it would do nothing.
            boolean offer = (canEdit || adminOffer) && !b.logView.filtered();
            editButton.setVisible(offer);
            editButton.setManaged(offer);
            note.setVisible(!canEdit && !adminOffer);
            note.setManaged(!canEdit && !adminOffer);
        }
        viewModeBarVisible = show;
        refreshTopBars();
    }

    /** Puts the active top bars into {@code outer.setTop}: the install banner above the view-mode banner
     *  (a {@code VBox} when both show), or {@code null} when neither does. */
    void refreshTopBars() {
        java.util.List<javafx.scene.Node> bars = new java.util.ArrayList<>(3);
        if (b.encryption.notice() != null) {
            bars.add(b.encryption.notice());
        }
        if (fileNoticeShown && fileNotice != null) {
            bars.add(fileNotice.node());
        }
        if (installBarShown && installBar != null) {
            bars.add(installBar.node());
        }
        if (viewModeBarVisible && viewModeBar != null) {
            bars.add(viewModeBar.node());
        }
        bars.addAll(b.narrowView.bars()); // the narrowing banner, nearest the text: it describes the text
        if (b.logControl != null) {
            bars.add(b.logControl);
        }
        if (bars.isEmpty()) {
            b.outer.setTop(null);
        } else if (bars.size() == 1) {
            b.outer.setTop(bars.get(0));
        } else {
            b.outer.setTop(new javafx.scene.layout.VBox(bars.toArray(new javafx.scene.Node[0])));
        }
    }

    /**
     * Sets the install banner's content + actions (built lazily). Shown/hidden via {@link #showInstallBar}.
     * {@code onInstall}/{@code onDismiss} are wired by MainController; the banner stays toolkit-only.
     */
    void setInstallPrompt(String message, String actionLabel, Runnable onInstall, Runnable onDismiss) {
        if (installBar == null) {
            installBar = new NoticeBar(tr("install.banner.dismiss"), "lsp-install-bar");
        }
        installBar.set(message, actionLabel, onInstall, onDismiss);
    }

    /** Shows or hides the install banner (no-op visual until {@link #setInstallPrompt} has set content). */
    void showInstallBar(boolean show) {
        installBarShown = show && installBar != null;
        refreshTopBars();
    }

    boolean isInstallBarShown() {
        return installBarShown;
    }

    /** Reflects an in-progress install: spins the indicator + disables the buttons. */
    void setInstallBarBusy(boolean busy) {
        if (installBar != null) {
            installBar.setBusy(busy);
        }
    }

    /** Shows a notice about the file above the editor, with one action; dismissing it only hides it. */
    void showFileNotice(String message, String actionLabel, String dismissLabel, Runnable onAction) {
        fileNotice = new NoticeBar(dismissLabel, "lsp-install-bar", "file-notice-bar");
        fileNotice.set(message, actionLabel, onAction, this::hideFileNotice);
        fileNoticeShown = true;
        refreshTopBars();
    }

    void hideFileNotice() {
        fileNoticeShown = false;
        refreshTopBars();
    }

    /** The file notice's message while it is shown, else null. */
    @Nullable
    String fileNoticeText() {
        return fileNoticeShown && fileNotice != null ? fileNotice.text() : null;
    }
}
