package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.editor.crypto.EncryptionState;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What an encrypted file looks like: the status-bar text and tooltips, the locked placeholder a tab shows
 * until the file is decrypted, the banner above a file whose save failed, and the details card the status-bar
 * item opens. Builders only — the actions are the caller's. FX thread.
 */
final class EncryptionViews {

    private EncryptionViews() {}

    // ---- words -------------------------------------------------------------------------------------------------

    /** "Encrypted · 2", "Encrypted · Passphrase", "Locked", with "(unsaved)" while new recipients wait. */
    static String chipText(EncryptionState state) {
        if (state.locked()) {
            return tr("encryption.chip.locked");
        }
        String base = state.mode() == EncryptionState.Mode.PASSPHRASE
                ? tr("encryption.chip.passphrase")
                : tr("encryption.chip.count", String.valueOf(state.recipients().size()));
        return state.recipientsPending() ? tr("encryption.chip.pending", base) : base;
    }

    static String chipTooltip(EncryptionState state) {
        return readableBy(state) + "\n" + tr("encryption.chip.tip");
    }

    /** The tab's tooltip: the path, then who can read the file. */
    static String tabTooltip(String path, EncryptionState state) {
        return path + "\n" + readableBy(state);
    }

    /** "Encrypted for Adrian De Leon (you) and Dana Ruiz." / "Encrypted with a passphrase." */
    static String readableBy(EncryptionState state) {
        if (state.mode() == EncryptionState.Mode.PASSPHRASE) {
            return tr("encryption.readable.passphrase");
        }
        if (state.recipients().isEmpty()) {
            return tr("encryption.readable.unknown");
        }
        return tr("encryption.readable.people", people(state.recipients()));
    }

    /** "Adrian De Leon (you), Dana Ruiz and 0x9F21…" in the reader's language. */
    static String people(List<EncryptionState.Recipient> recipients) {
        List<String> names = new ArrayList<>();
        for (EncryptionState.Recipient r : recipients) {
            names.add(r.you() ? tr("encryption.you", r.label()) : r.label());
        }
        return joinNames(names);
    }

    /** "A", "A and B", "A, B and C" — the conjunction from the catalog. */
    static String joinNames(List<String> names) {
        if (names.isEmpty()) {
            return "";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        String head = String.join(", ", names.subList(0, names.size() - 1));
        return tr("encryption.and", head, names.get(names.size() - 1));
    }

    // ---- the locked placeholder --------------------------------------------------------------------------------

    /** What the locked placeholder can do; each may be null when it does not apply. */
    record LockedActions(
            Runnable decrypt,
            Runnable cancel,
            @Nullable Runnable showEncryptedText,
            Runnable decryptToFile,
            Runnable openDoctor,
            Runnable setGpgPath) {}

    /**
     * The tab's content while {@code fileName} is locked: a lock, what happened, who the file is for, and the
     * next step as a button that has the focus (Enter takes it). Never a hex dump or an error dialog.
     */
    static Node lockedView(
            String fileName, EncryptionState state, LockedActions actions, @Nullable String encryptedText) {
        EncryptionState.Lock lock = state.lock() == null ? EncryptionState.Lock.RESTORED : state.lock();
        Node icon = Icons.lock();
        icon.getStyleClass().add("encryption-locked-icon");
        icon.setScaleX(2.0);
        icon.setScaleY(2.0);
        StackPane iconBox = new StackPane(icon);
        iconBox.setMinHeight(48);

        Label title = new Label(
                switch (lock) {
                    case DECRYPTING -> tr("encryption.locked.decryptingTitle", fileName);
                    case NOT_INSTALLED -> tr("encryption.locked.notInstalledTitle");
                    default -> tr("encryption.locked.title", fileName);
                });
        title.getStyleClass().add("encryption-locked-title");
        title.setWrapText(true);

        VBox box = new VBox(8, iconBox, title);
        box.getStyleClass().add("encryption-locked");
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(560);
        box.setPadding(new Insets(28));

        String who = state.recipients().isEmpty() && state.mode() == EncryptionState.Mode.PUBLIC_KEY
                ? null
                : readableBy(state);
        String message = switch (lock) {
            case RESTORED -> null;
            case DECRYPTING -> tr("encryption.locked.decrypting");
            case CANCELLED -> tr("encryption.locked.cancelled");
            case BAD_PASSPHRASE -> tr("encryption.locked.badPassphrase");
            case NO_SECRET_KEY -> tr("encryption.locked.noSecretKey");
            case NO_PINENTRY -> tr("encryption.locked.noPinentry");
            case NOT_INSTALLED -> tr("encryption.locked.notInstalled", installHint());
            case NOT_TEXT -> tr("encryption.locked.notText");
            case TOO_LARGE -> tr("encryption.locked.tooLarge");
            case FAILED -> tr("encryption.locked.failed");
        };
        if (who != null && lock != EncryptionState.Lock.NOT_INSTALLED) {
            box.getChildren().add(body(who));
        }
        if (message != null) {
            box.getChildren().add(body(message));
        }

        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER);
        Button primary = null;
        switch (lock) {
            case DECRYPTING -> {
                ProgressIndicator spinner = new ProgressIndicator();
                spinner.setPrefSize(18, 18);
                buttons.getChildren().add(spinner);
                primary = add(buttons, tr("dialog.cancel"), actions.cancel(), false);
            }
            case RESTORED, CANCELLED, NO_PINENTRY ->
                primary = add(buttons, tr("encryption.action.decrypt"), actions.decrypt(), true);
            case BAD_PASSPHRASE, FAILED ->
                primary = add(buttons, tr("encryption.action.tryAgain"), actions.decrypt(), true);
            case NOT_INSTALLED -> {
                primary = add(buttons, tr("encryption.action.openDoctor"), actions.openDoctor(), true);
                add(buttons, tr("encryption.action.setGpgPath"), actions.setGpgPath(), false);
            }
            case NOT_TEXT, TOO_LARGE ->
                primary = add(buttons, tr("encryption.action.decryptToFile"), actions.decryptToFile(), true);
            case NO_SECRET_KEY -> {
                // Nothing to retry: the next step is outside Editora (get the right key).
            }
        }
        if (lock != EncryptionState.Lock.DECRYPTING && actions.showEncryptedText() != null && encryptedText == null) {
            Button show = add(buttons, tr("encryption.action.showEncrypted"), actions.showEncryptedText(), false);
            if (primary == null) {
                primary = show;
            }
        }
        if (!buttons.getChildren().isEmpty()) {
            box.getChildren().add(buttons);
        }
        if (!state.lockDetail().isBlank() && lock != EncryptionState.Lock.DECRYPTING) {
            box.getChildren().add(details(state.lockDetail()));
        }
        if (encryptedText != null) {
            TextArea armor = new TextArea(encryptedText);
            armor.setEditable(false);
            armor.getStyleClass().add("encryption-armor");
            armor.setPrefRowCount(10);
            box.getChildren().add(armor);
        }
        if (primary != null && lock == EncryptionState.Lock.RESTORED) {
            Label hint = new Label(tr("encryption.locked.enterHint"));
            hint.getStyleClass().add("encryption-locked-hint");
            box.getChildren().add(hint);
        }

        StackPane root = new StackPane(box);
        root.getStyleClass().add("encryption-locked-pane");
        if (primary != null) {
            focusWhenShown(root, primary);
        }
        return root;
    }

    /**
     * Gives {@code target} the focus whenever {@code root} comes on screen — its tab selected — so Enter takes the
     * next step. Not while it is hidden: a session restore builds every locked tab at once, and a hidden one
     * must not take the focus from the editor the user is looking at.
     */
    static void focusWhenShown(Node root, Node target) {
        javafx.beans.InvalidationListener check = o -> javafx.application.Platform.runLater(() -> {
            if (showing(root) && !root.isDisabled()) {
                target.requestFocus();
            }
        });
        root.getProperties().put("editora.focusWhenShown", check); // the weak listeners below hold it through this
        javafx.beans.WeakInvalidationListener weak = new javafx.beans.WeakInvalidationListener(check);
        root.sceneProperty().addListener((o, was, scene) -> {
            if (scene == null) {
                return;
            }
            // A tab pane hides an unselected tab's content: watch every ancestor's visibility.
            for (Node n = root.getParent(); n != null; n = n.getParent()) {
                n.visibleProperty().removeListener(weak);
                n.visibleProperty().addListener(weak);
            }
            check.invalidated(null);
        });
    }

    /** Whether {@code node} and every ancestor are visible and it is in a scene. */
    static boolean showing(Node node) {
        if (node.getScene() == null) {
            return false;
        }
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static Label body(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("encryption-locked-body");
        l.setWrapText(true);
        l.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        return l;
    }

    private static @Nullable Button add(HBox row, String label, Runnable action, boolean accent) {
        if (action == null) {
            return null;
        }
        Button b = new Button(label);
        if (accent) {
            b.getStyleClass().add("accent");
        }
        b.setOnAction(e -> action.run());
        b.setDefaultButton(false);
        row.getChildren().add(b);
        return b;
    }

    /** A "Details" link that reveals gpg's own words under it. */
    static Node details(String text) {
        Label detail = new Label(text);
        detail.getStyleClass().add("encryption-details");
        detail.setWrapText(true);
        detail.setVisible(false);
        detail.setManaged(false);
        Hyperlink toggle = new Hyperlink(tr("encryption.action.details"));
        toggle.setOnAction(e -> {
            boolean show = !detail.isVisible();
            detail.setVisible(show);
            detail.setManaged(show);
        });
        VBox v = new VBox(2, toggle, detail);
        v.setAlignment(Pos.CENTER);
        return v;
    }

    /** The install line for this OS, in the reader's language. */
    static String installHint() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac")) {
            return tr("encryption.install.mac");
        }
        if (os.contains("win")) {
            return tr("encryption.install.windows");
        }
        return tr("encryption.install.linux");
    }

    // ---- the banner --------------------------------------------------------------------------------------------

    /** An action on the banner. */
    record BannerAction(String label, Runnable action) {}

    /**
     * The red "Not saved." banner above the editor: the reason, its actions (the first is primary), a Details
     * link with gpg's own words, and a dismiss button.
     */
    static Node saveFailedBanner(
            @Nullable String reason, List<BannerAction> actions, @Nullable String detail, Runnable dismiss) {
        Label lead = new Label(tr("encryption.banner.notSaved"));
        lead.getStyleClass().add("encryption-banner-lead");
        lead.setMinWidth(Region.USE_PREF_SIZE); // never "N…": the message wraps instead
        Label text = new Label(reason);
        text.getStyleClass().add("encryption-banner-text");
        text.setWrapText(true);
        text.setMinWidth(120);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(8, lead, text);
        row.setAlignment(Pos.CENTER_LEFT);
        boolean first = true;
        for (BannerAction a : actions) {
            Button b = new Button(a.label());
            b.setMinWidth(Region.USE_PREF_SIZE);
            if (first) {
                b.getStyleClass().add("accent");
            }
            first = false;
            b.setOnAction(e -> a.action().run());
            row.getChildren().add(b);
        }
        VBox banner = new VBox(4, row);
        if (detail != null && !detail.isBlank()) {
            Label detailLabel = new Label(detail);
            detailLabel.getStyleClass().add("encryption-details");
            detailLabel.setWrapText(true);
            detailLabel.setVisible(false);
            detailLabel.setManaged(false);
            Hyperlink toggle = new Hyperlink(tr("encryption.action.details"));
            toggle.setMinWidth(Region.USE_PREF_SIZE);
            toggle.setOnAction(e -> {
                boolean show = !detailLabel.isVisible();
                detailLabel.setVisible(show);
                detailLabel.setManaged(show);
            });
            row.getChildren().add(toggle);
            banner.getChildren().add(detailLabel);
        }
        Button close = new Button("×");
        close.getStyleClass().addAll("lsp-install-dismiss", "flat");
        close.setOnAction(e -> dismiss.run());
        row.getChildren().add(close);
        banner.getStyleClass().addAll("lsp-install-bar", "encryption-banner");
        return banner;
    }

    /** The blue "Encrypted for …" banner after Encrypt File, about the plain original still on disk. */
    static Node originalStillOnDiskBanner(String message, List<BannerAction> actions, Runnable dismiss) {
        Label text = new Label(message);
        text.setWrapText(true);
        text.getStyleClass().add("lsp-install-title");
        text.setMinWidth(120);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(8, text);
        row.setAlignment(Pos.CENTER_LEFT);
        boolean first = true;
        for (BannerAction a : actions) {
            Button b = new Button(a.label());
            b.setMinWidth(Region.USE_PREF_SIZE);
            if (first) {
                b.getStyleClass().add("accent");
            }
            first = false;
            b.setOnAction(e -> a.action().run());
            row.getChildren().add(b);
        }
        Button close = new Button("×");
        close.getStyleClass().addAll("lsp-install-dismiss", "flat");
        close.setOnAction(e -> dismiss.run());
        row.getChildren().add(close);
        row.getStyleClass().addAll("lsp-install-bar", "encryption-info-banner");
        return row;
    }

    // ---- the details card --------------------------------------------------------------------------------------

    /** What the details card offers. */
    record InfoActions(Runnable changeRecipients, Runnable removeEncryption, Runnable lock) {}

    /**
     * The card the status-bar item opens: who can read the file (you, verified or not, keys missing from the
     * keyring), its format, a signature, that crash recovery is off — and the three things to do about it.
     * Up/Down move between the actions.
     */
    static VBox infoCard(EncryptionState state, InfoActions actions, Runnable close) {
        Node icon = Icons.lock();
        Label heading = new Label(tr("encryption.info.title"));
        heading.getStyleClass().add("overlay-title");
        HBox head = new HBox(8, icon, heading);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(10, head);
        card.getStyleClass().addAll("command-palette", "overlay-form", "encryption-info");
        card.setPadding(new Insets(14));
        card.setPrefWidth(360);
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        VBox people = section(tr("encryption.info.readableBy"));
        if (state.mode() == EncryptionState.Mode.PASSPHRASE) {
            people.getChildren().add(infoRow(tr("encryption.info.anyoneWithPassphrase")));
        } else {
            for (EncryptionState.Recipient r : state.recipients()) {
                HBox row = infoRow(r.label());
                if (r.you()) {
                    row.getChildren().add(pill(tr("encryption.pill.you"), "encryption-pill-you"));
                }
                if (!r.known()) {
                    row.getChildren().add(pill(tr("encryption.pill.noKey"), "encryption-pill-red"));
                } else {
                    row.getChildren()
                            .add(
                                    r.verified()
                                            ? pill(tr("encryption.pill.verified"), "encryption-pill-green")
                                            : pill(tr("encryption.pill.notVerified"), "encryption-pill-amber"));
                }
                people.getChildren().add(row);
            }
        }
        card.getChildren().add(people);

        VBox file = section(tr("encryption.info.file"));
        file.getChildren()
                .add(keyValue(
                        tr("encryption.info.format"),
                        state.armored() ? tr("encryption.format.armor") : tr("encryption.format.binary")));
        if (state.signature() != null) {
            file.getChildren().add(keyValue(tr("encryption.info.signature"), state.signature()));
        }
        HBox recovery = infoRow(tr("encryption.info.recovery"));
        recovery.getChildren().add(pill(tr("encryption.info.recoveryOff"), "encryption-pill-muted"));
        file.getChildren().add(recovery);
        if (state.recipientsPending()) {
            file.getChildren().add(infoRow(tr("encryption.info.pending")));
        }
        card.getChildren().add(file);

        List<Button> buttons = new ArrayList<>();
        buttons.add(actionButton(tr("encryption.action.changeRecipients"), actions.changeRecipients(), close));
        buttons.add(actionButton(tr("encryption.action.removeEncryption"), actions.removeEncryption(), close));
        buttons.add(actionButton(tr("encryption.action.lock"), actions.lock(), close));
        VBox acts = new VBox(2);
        acts.getStyleClass().add("encryption-info-actions");
        acts.getChildren().addAll(buttons);
        card.getChildren().add(acts);
        card.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            int i = buttons.indexOf(e.getTarget());
            if (e.getCode() == KeyCode.DOWN && i >= 0 && i < buttons.size() - 1) {
                buttons.get(i + 1).requestFocus();
                e.consume();
            } else if (e.getCode() == KeyCode.UP && i > 0) {
                buttons.get(i - 1).requestFocus();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && i >= 0) {
                buttons.get(i).fire();
                e.consume();
            }
        });
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        card.getProperties().put("editora.firstFocus", buttons.get(0));
        return card;
    }

    private static VBox section(String title) {
        Label l = new Label(title.toUpperCase(java.util.Locale.ROOT));
        l.getStyleClass().add("encryption-info-section");
        return new VBox(4, l);
    }

    private static HBox infoRow(String text) {
        Label l = new Label(text);
        l.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(l, Priority.ALWAYS);
        HBox row = new HBox(6, l);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static HBox keyValue(String key, String value) {
        HBox row = infoRow(key);
        Label v = new Label(value);
        v.getStyleClass().add("encryption-info-value");
        row.getChildren().add(v);
        return row;
    }

    /** A small tinted pill (You / Verified / Not verified / …). */
    static Label pill(String text, String variant) {
        Label p = new Label(text);
        p.getStyleClass().addAll("state-pill", "encryption-pill", variant);
        return p;
    }

    private static Button actionButton(String label, Runnable action, Runnable close) {
        Button b = new Button(label);
        b.getStyleClass().addAll("flat", "encryption-info-action");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setOnAction(e -> {
            close.run();
            if (action != null) {
                action.run();
            }
        });
        return b;
    }
}
