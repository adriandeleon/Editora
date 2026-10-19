package com.editora.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.crypto.Gpg;
import com.editora.crypto.GpgKey;
import com.editora.editor.EncryptionState;

import static com.editora.i18n.Messages.tr;

/**
 * Who a file is encrypted for: an in-scene card listing the keyring, searchable, several people at once — or a
 * passphrase instead. Each row says who the key is ({@code You} for a key whose secret is here), whether it is
 * verified, and, for a key that cannot be used (expired, revoked, sign-only), why, instead of hiding it. The
 * user's own key starts ticked, so they can open the file again; untick it and the card says what that means.
 *
 * <p>Keyboard first: typing searches, {@code ↑}/{@code ↓} move, {@code Space} ticks, {@code Enter} accepts,
 * {@code Esc} cancels. FX thread.
 */
final class RecipientPicker {

    private RecipientPicker() {}

    /**
     * What to show.
     *
     * @param title       the card title ("Encrypt notes.md")
     * @param keys        the keyring
     * @param preselected fingerprints ticked from the start (the file's recipients, a folder's defaults, you)
     * @param recent      fingerprints most recently encrypted for, newest first (listed early)
     * @param mode        the mode the card opens in
     * @param applyOnSave the change waits for the next save ("Apply on Save") rather than writing now
     * @param formatShown whether the ASCII armor / binary choice is offered (a new file; a name decides otherwise)
     * @param armor       the format's starting value
     * @param folderName  offer "use these for new files in {folder}/" (writes {@code .gpg-id}); null hides it
     */
    record Request(
            String title,
            List<GpgKey> keys,
            Set<String> preselected,
            List<String> recent,
            EncryptionState.Mode mode,
            boolean applyOnSave,
            boolean formatShown,
            boolean armor,
            String folderName) {

        /** The same request over a keyring read again (after an import). */
        Request withKeys(List<GpgKey> fresh) {
            return new Request(title, fresh, preselected, recent, mode, applyOnSave, formatShown, armor, folderName);
        }
    }

    /** What the user chose. The passphrase (passphrase mode only) is the caller's to wipe. */
    record Choice(
            EncryptionState.Mode mode,
            List<GpgKey> keys,
            char[] passphrase,
            boolean armor,
            boolean rememberForFolder) {}

    /** The keys in the order the card lists them: yours, then recent, then by name; unusable ones last. */
    static List<GpgKey> ordered(List<GpgKey> keys, List<String> recent) {
        List<GpgKey> out = new ArrayList<>(keys);
        out.sort(Comparator.<GpgKey>comparingInt(k -> k.canEncrypt() ? 0 : 1)
                .thenComparingInt(k -> k.secret() ? 0 : 1)
                .thenComparingInt(k -> {
                    int i = recent.indexOf(k.fingerprint());
                    return i < 0 ? Integer.MAX_VALUE : i;
                })
                .thenComparing(k -> k.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Whether {@code key} matches a search: name, email or key id / fingerprint, case-insensitive. */
    static boolean matches(GpgKey key, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        for (String uid : key.userIds()) {
            if (uid.toLowerCase(Locale.ROOT).replace(" ", "").contains(q)) {
                return true;
            }
        }
        String hex = q.startsWith("0x") ? q.substring(2) : q;
        return key.fingerprint().toLowerCase(Locale.ROOT).contains(hex);
    }

    /** Why a key cannot be chosen, in words, or null. */
    static String unusableText(GpgKey key) {
        GpgKey.Unusable why = key.unusable();
        if (why == null) {
            return null;
        }
        return switch (why) {
            case EXPIRED ->
                key.expires() > 0
                        ? tr(
                                "encryption.key.expiredOn",
                                java.time.LocalDate.ofInstant(
                                                java.time.Instant.ofEpochSecond(key.expires()),
                                                java.time.ZoneId.systemDefault())
                                        .toString())
                        : tr("encryption.key.expired");
            case REVOKED -> tr("encryption.key.revoked");
            case DISABLED -> tr("encryption.key.disabled");
            case INVALID -> tr("encryption.key.invalid");
            case NO_ENCRYPTION -> tr("encryption.key.signOnly");
        };
    }

    static void show(OverlayHost host, Request req, Consumer<Choice> onAccept, Runnable onImport, Runnable onCancel) {
        if (host == null) {
            return;
        }
        boolean[] accepted = {false};
        boolean haveSecret = req.keys().stream().anyMatch(k -> k.secret() && k.canEncrypt());
        Set<String> ticked = new LinkedHashSet<>();
        for (GpgKey k : req.keys()) {
            if (k.canEncrypt() && req.preselected().contains(k.fingerprint())) {
                ticked.add(k.fingerprint());
            }
        }

        Label title = new Label(req.title());
        title.getStyleClass().add("overlay-title");
        ToggleGroup modes = new ToggleGroup();
        ToggleButton keysMode = new ToggleButton(tr("encryption.picker.publicKeys"));
        ToggleButton passMode = new ToggleButton(tr("encryption.picker.passphrase"));
        keysMode.setToggleGroup(modes);
        passMode.setToggleGroup(modes);
        keysMode.getStyleClass().add("left-pill");
        passMode.getStyleClass().add("right-pill");
        keysMode.setFocusTraversable(false);
        passMode.setFocusTraversable(false);
        HBox modeBox = new HBox(0, keysMode, passMode);
        modeBox.getStyleClass().add("encryption-mode");
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        HBox head = new HBox(10, title, headSpacer, modeBox);
        head.setAlignment(Pos.CENTER_LEFT);

        // ---- public keys pane -------------------------------------------------------------------------------
        FlowPane chips = new FlowPane(6, 6);
        chips.getStyleClass().add("encryption-chips");
        TextField search = new TextField();
        search.setPromptText(tr("encryption.picker.search"));
        search.getStyleClass().add("encryption-search");
        search.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (" ".equals(e.getCharacter())) {
                e.consume(); // Space ticks the row (see the key filter below); it never types into the search
            }
        });
        FilteredList<GpgKey> rows =
                new FilteredList<>(FXCollections.observableArrayList(ordered(req.keys(), req.recent())), k -> true);
        ListView<GpgKey> list = new ListView<>(rows);
        list.getStyleClass().add("encryption-key-list");
        list.setPrefHeight(Math.min(300, 52.0 * Math.max(3, rows.size()) + 4));
        list.setFocusTraversable(false); // the search field keeps focus; arrows drive the list from there
        Label warning = new Label();
        warning.getStyleClass().add("encryption-warning");
        warning.setWrapText(true);
        warning.setMaxWidth(Double.MAX_VALUE);
        Runnable[] refresh = new Runnable[1];

        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(GpgKey key, boolean empty) {
                super.updateItem(key, empty);
                if (empty || key == null) {
                    setGraphic(null);
                    setText(null);
                    setOnMouseClicked(null);
                    return;
                }
                CheckBox box = new CheckBox();
                box.setSelected(ticked.contains(key.fingerprint()));
                box.setDisable(!key.canEncrypt());
                box.setMouseTransparent(true);
                box.setFocusTraversable(false);
                Label name = new Label(key.name());
                name.getStyleClass().add("encryption-key-name");
                Label email = new Label(key.email());
                email.getStyleClass().add("encryption-key-email");
                HBox top = new HBox(6, name, email);
                Label fpr = new Label(key.shortFingerprint());
                fpr.getStyleClass().add("encryption-key-fpr");
                VBox who = new VBox(1, top, fpr);
                HBox.setHgrow(who, Priority.ALWAYS);
                HBox tags = new HBox(4);
                tags.setAlignment(Pos.CENTER_RIGHT);
                String unusable = unusableText(key);
                if (key.secret()) {
                    tags.getChildren().add(EncryptionViews.pill(tr("encryption.pill.you"), "encryption-pill-you"));
                }
                if (unusable != null) {
                    tags.getChildren().add(EncryptionViews.pill(unusable, "encryption-pill-red"));
                } else {
                    tags.getChildren()
                            .add(
                                    key.verified()
                                            ? EncryptionViews.pill(
                                                    tr("encryption.pill.verified"), "encryption-pill-green")
                                            : EncryptionViews.pill(
                                                    tr("encryption.pill.notVerified"), "encryption-pill-amber"));
                }
                HBox row = new HBox(10, box, who, tags);
                row.setAlignment(Pos.CENTER_LEFT);
                if (unusable != null) {
                    row.getStyleClass().add("encryption-key-unusable");
                }
                setText(null);
                setGraphic(row);
                setOnMouseClicked(e -> {
                    toggle(key, ticked);
                    refresh[0].run();
                });
            }
        });

        VBox empty = new VBox(8);
        empty.getStyleClass().add("encryption-empty");
        Label emptyText = new Label(tr("encryption.picker.noKeys"));
        emptyText.setWrapText(true);
        Button usePass = new Button(tr("encryption.picker.usePassphrase"));
        usePass.setOnAction(e -> modes.selectToggle(passMode));
        Button importKey = new Button(tr("encryption.action.importKey"));
        importKey.setOnAction(e -> {
            host.hide();
            onImport.run();
        });
        empty.getChildren().addAll(emptyText, new HBox(8, usePass, importKey));
        boolean noKeys = req.keys().stream().noneMatch(GpgKey::canEncrypt);

        VBox keysPane = new VBox(8);
        if (noKeys) {
            keysPane.getChildren().add(empty);
        } else {
            keysPane.getChildren().addAll(chips, search, list, warning);
        }

        // ---- passphrase pane --------------------------------------------------------------------------------
        PasswordField pass1 = new PasswordField();
        PasswordField pass2 = new PasswordField();
        pass1.setPromptText(tr("encryption.picker.passphraseNew"));
        pass2.setPromptText(tr("encryption.picker.passphraseRepeat"));
        Label passNote = new Label(tr("encryption.picker.passphraseNote"));
        passNote.getStyleClass().add("overlay-note");
        passNote.setWrapText(true);
        Label passMatch = new Label();
        passMatch.getStyleClass().add("encryption-warning");
        VBox passPane = new VBox(8, passNote, pass1, pass2, passMatch);

        // ---- options + footer -------------------------------------------------------------------------------
        ComboBox<Boolean> format = new ComboBox<>(FXCollections.observableArrayList(false, true));
        format.setValue(req.armor());
        format.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Boolean armor) {
                return Boolean.TRUE.equals(armor) ? tr("encryption.format.armor") : tr("encryption.format.binary");
            }

            @Override
            public Boolean fromString(String s) {
                return null;
            }
        });
        Label formatLabel = new Label(tr("encryption.info.format"));
        HBox formatRow = new HBox(6, formatLabel, format);
        formatRow.setAlignment(Pos.CENTER_LEFT);
        CheckBox remember =
                new CheckBox(req.folderName() == null ? "" : tr("encryption.picker.rememberFolder", req.folderName()));
        HBox options = new HBox(16);
        options.setAlignment(Pos.CENTER_LEFT);
        if (req.formatShown()) {
            options.getChildren().add(formatRow);
        }
        if (req.folderName() != null) {
            options.getChildren().add(remember);
        }

        Label keysHint = new Label(tr("encryption.picker.keys"));
        keysHint.getStyleClass().addAll("overlay-hint", "chord-chip");

        Hyperlink importLink = new Hyperlink(tr("encryption.action.importKey"));
        importLink.setOnAction(e -> {
            host.hide();
            onImport.run();
        });
        Button cancel = new Button(tr("dialog.cancel"));
        cancel.setOnAction(e -> host.hide());
        Button ok = new Button();
        ok.getStyleClass().add("accent");
        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);
        HBox footer = new HBox(8, importLink, footSpacer, cancel, ok);
        footer.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(8);
        VBox card = new VBox(10, head, content, options, keysHint, footer);
        card.getStyleClass().addAll("command-palette", "overlay-form", "encryption-picker");
        card.setPadding(new Insets(14));
        card.setPrefWidth(600);
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);

        SimpleBooleanProperty valid = new SimpleBooleanProperty();
        refresh[0] = () -> {
            boolean keyMode = modes.getSelectedToggle() != passMode;
            content.getChildren().setAll(keyMode ? keysPane : passPane);
            options.setVisible(!options.getChildren().isEmpty());
            options.setManaged(options.isVisible());
            keysHint.setVisible(keyMode && !noKeys);
            keysHint.setManaged(keysHint.isVisible());
            chips.getChildren().clear();
            List<GpgKey> chosen = chosen(req.keys(), ticked);
            for (GpgKey k : chosen) {
                Label chipName = new Label(k.secret() ? tr("encryption.you", k.name()) : k.name());
                Button x = new Button("×");
                x.getStyleClass().addAll("flat", "encryption-chip-remove");
                x.setFocusTraversable(false);
                x.setOnAction(e -> {
                    ticked.remove(k.fingerprint());
                    refresh[0].run();
                });
                HBox chip = new HBox(2, chipName, x);
                chip.setAlignment(Pos.CENTER_LEFT);
                chip.getStyleClass().add("encryption-chip");
                chips.getChildren().add(chip);
            }
            chips.setVisible(!chosen.isEmpty());
            chips.setManaged(!chosen.isEmpty());
            String warn = keyMode ? warningFor(chosen, haveSecret) : null;
            warning.setText(warn == null ? "" : warn);
            warning.setVisible(warn != null);
            warning.setManaged(warn != null);
            list.refresh();
            if (keyMode) {
                valid.set(!chosen.isEmpty());
                boolean withoutMe = haveSecret && chosen.stream().noneMatch(GpgKey::secret);
                ok.setText(
                        req.applyOnSave()
                                ? tr("encryption.picker.applyOnSave")
                                : withoutMe
                                        ? tr("encryption.picker.encryptWithoutMe")
                                        : chosen.size() == 1
                                                ? tr("encryption.picker.encryptForOne")
                                                : tr("encryption.picker.encryptFor", String.valueOf(chosen.size())));
            } else {
                char[] a = pass1.getText().toCharArray();
                char[] b = pass2.getText().toCharArray();
                boolean same = Arrays.equals(a, b);
                boolean acceptable = Gpg.acceptable(a);
                Arrays.fill(a, '\0');
                Arrays.fill(b, '\0');
                valid.set(same && acceptable);
                passMatch.setText(pass2.getText().isEmpty() || same ? "" : tr("encryption.picker.passphraseMismatch"));
                ok.setText(
                        req.applyOnSave()
                                ? tr("encryption.picker.applyOnSave")
                                : tr("encryption.picker.encryptWithPassphrase"));
            }
            ok.setDisable(!valid.get());
        };
        modes.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                modes.selectToggle(was); // a segmented control always has one side on
                return;
            }
            refresh[0].run();
            (now == passMode ? pass1 : noKeys ? usePass : search).requestFocus();
        });
        search.textProperty().addListener((o, was, now) -> {
            rows.setPredicate(k -> matches(k, now));
            if (!rows.isEmpty()) {
                list.getSelectionModel().select(0);
            }
        });
        pass1.textProperty().addListener((o, was, now) -> refresh[0].run());
        pass2.textProperty().addListener((o, was, now) -> refresh[0].run());

        Runnable accept = () -> {
            if (!valid.get()) {
                return;
            }
            boolean keyMode = modes.getSelectedToggle() != passMode;
            char[] passphrase = keyMode ? null : pass1.getText().toCharArray();
            Choice choice = new Choice(
                    keyMode ? EncryptionState.Mode.PUBLIC_KEY : EncryptionState.Mode.PASSPHRASE,
                    keyMode ? chosen(req.keys(), ticked) : List.of(),
                    passphrase,
                    Boolean.TRUE.equals(format.getValue()),
                    req.folderName() != null && remember.isSelected() && keyMode);
            pass1.clear();
            pass2.clear();
            accepted[0] = true;
            host.hide();
            onAccept.accept(choice);
        };
        ok.setOnAction(e -> accept.run());
        card.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            boolean keyMode = modes.getSelectedToggle() != passMode;
            if (e.getCode() == KeyCode.ENTER) {
                if (e.getTarget() instanceof Button b && b != ok) {
                    b.fire(); // the button the user tabbed to
                } else {
                    accept.run();
                }
                e.consume();
            } else if (keyMode && !noKeys && e.getCode() == KeyCode.SPACE && !(e.getTarget() instanceof Button)) {
                // The search ignores spaces ("danaruiz" finds "Dana Ruiz"), so Space is free to tick the row.
                GpgKey focused = list.getSelectionModel().getSelectedItem();
                if (focused != null) {
                    toggle(focused, ticked);
                    refresh[0].run();
                }
                e.consume();
            } else if (keyMode
                    && !noKeys
                    && e.getTarget() == search
                    && PickerKeys.navigate(
                            list, PickerKeys.action(e, com.editora.command.TextInputKeymap.sharedKeymap(), false))) {
                e.consume();
            }
        });

        modes.selectToggle(req.mode() == EncryptionState.Mode.PASSPHRASE ? passMode : keysMode);
        refresh[0].run();
        if (!rows.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        host.show(
                card,
                true,
                () -> (req.mode() == EncryptionState.Mode.PASSPHRASE ? pass1 : noKeys ? usePass : search)
                        .requestFocus(),
                () -> {
                    pass1.clear();
                    pass2.clear();
                    if (!accepted[0] && onCancel != null) {
                        onCancel.run();
                    }
                });
    }

    private static void toggle(GpgKey key, Set<String> ticked) {
        if (!key.canEncrypt()) {
            return;
        }
        if (!ticked.remove(key.fingerprint())) {
            ticked.add(key.fingerprint());
        }
    }

    private static List<GpgKey> chosen(List<GpgKey> keys, Set<String> ticked) {
        List<GpgKey> out = new ArrayList<>();
        for (String fpr : ticked) {
            keys.stream().filter(k -> k.fingerprint().equals(fpr)).findFirst().ifPresent(out::add);
        }
        return out;
    }

    /**
     * The warning under the chips: an unverified key (with its full fingerprint, to compare with its owner), or
     * that the user will not be able to open the file. Null when there is nothing to say.
     */
    static String warningFor(List<GpgKey> chosen, boolean haveSecret) {
        List<String> parts = new ArrayList<>();
        for (GpgKey k : chosen) {
            if (!k.verified()) {
                parts.add(tr("encryption.picker.unverified", k.name(), k.groupedFingerprint()));
                break; // one at a time: the first is the one to check now
            }
        }
        if (haveSecret && !chosen.isEmpty() && chosen.stream().noneMatch(GpgKey::secret)) {
            parts.add(tr("encryption.picker.withoutMe"));
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }
}
