package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.crypto.GpgKey;
import com.editora.editor.crypto.EncryptionState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static com.editora.ui.RecipientPickerTest.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recipient card on screen: ticking with Space and the mouse, the chips, the warning and the button's words,
 * the passphrase side, the empty keyring, and what accepting or cancelling hands back.
 */
@Tag("fx")
class RecipientPickerFxTest {

    private static final GpgKey ME = key("A".repeat(40), "Ada Lovelace <ada@example.org>", 'u', "ESCA", true);
    private static final GpgKey DANA = key("B".repeat(40), "Dana Ruiz <dana@example.org>", 'f', "E", false);
    private static final GpgKey ZED = key("C".repeat(40), "Zed Zane <zed@example.org>", '-', "E", false);
    private static final GpgKey GONE = key("E".repeat(40), "Gone Away <gone@example.org>", 'r', "E", false);

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A shown card and what it handed back. */
    private record Shown(
            Stage stage,
            OverlayHost host,
            Node card,
            List<RecipientPicker.Choice> accepted,
            int[] imports,
            int[] cancels) {

        <T extends Node> T find(String selector, Class<T> type) {
            return type.cast(card.lookup(selector));
        }

        TextField search() {
            return find(".encryption-search", TextField.class);
        }

        @SuppressWarnings("unchecked")
        ListView<GpgKey> list() {
            return (ListView<GpgKey>) card.lookup(".encryption-key-list");
        }

        Button ok() {
            return find(".accent", Button.class);
        }

        FlowPane chips() {
            return find(".encryption-chips", FlowPane.class);
        }

        String warning() {
            return card.lookupAll(".encryption-warning").stream()
                    .map(n -> ((Label) n).getText())
                    .filter(t -> !t.isEmpty())
                    .findFirst()
                    .orElse("");
        }

        Button button(String text) {
            return card.lookupAll(".button").stream()
                    .filter(n -> n instanceof Button b && text.equals(b.getText()))
                    .map(Button.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no button " + text));
        }

        List<PasswordField> passwords() {
            return card.lookupAll(".password-field").stream()
                    .map(PasswordField.class::cast)
                    .toList();
        }

        void press(Node target, KeyCode code) {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        }

        void layout() {
            card.getScene().getRoot().applyCss();
            card.getScene().getRoot().layout();
        }

        List<ListCell<?>> filledCells() {
            layout();
            List<ListCell<?>> out = new ArrayList<>();
            for (Node n : list().lookupAll(".list-cell")) {
                if (n instanceof ListCell<?> c && c.getItem() != null && c.getGraphic() != null) {
                    out.add(c);
                }
            }
            return out;
        }
    }

    private static RecipientPicker.Request request(
            List<GpgKey> keys,
            Set<String> preselected,
            EncryptionState.Mode mode,
            boolean applyOnSave,
            boolean formatShown,
            String folder) {
        return new RecipientPicker.Request(
                "Encrypt notes.md", keys, preselected, List.of(), mode, applyOnSave, formatShown, false, folder);
    }

    private static Shown show(RecipientPicker.Request req) {
        StackPane root = new StackPane();
        Stage stage = new Stage();
        stage.setScene(new Scene(root, 900, 700));
        stage.show();
        OverlayHost host = new OverlayHost();
        host.install(root);
        List<RecipientPicker.Choice> accepted = new ArrayList<>();
        int[] imports = {0};
        int[] cancels = {0};
        RecipientPicker.show(host, req, accepted::add, () -> imports[0]++, () -> cancels[0]++);
        Node card = root.lookup(".encryption-picker");
        assertNotNull(card);
        return new Shown(stage, host, card, accepted, imports, cancels);
    }

    private static MouseEvent click() {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                5,
                5,
                5,
                5,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                true,
                null);
    }

    @Test
    void withoutAHostNothingIsShown() throws Exception {
        FxTestSupport.runOnFx(() -> RecipientPicker.show(
                null,
                request(List.of(ME), Set.of(), EncryptionState.Mode.PUBLIC_KEY, false, false, null),
                c -> {
                    throw new AssertionError("accepted");
                },
                () -> {},
                null));
    }

    @Test
    void spaceTicksTheHighlightedRowAndTheButtonSaysWhatWillHappen() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(request(
                    List.of(GONE, ZED, DANA, ME),
                    Set.of(ME.fingerprint(), GONE.fingerprint()),
                    EncryptionState.Mode.PUBLIC_KEY,
                    false,
                    false,
                    null));
            assertEquals(List.of(ME, DANA, ZED, GONE), s.list().getItems(), "yours first, the revoked key last");
            assertEquals(1, s.chips().getChildren().size(), "a revoked key cannot be preselected");
            assertEquals(tr("encryption.picker.encryptForOne"), s.ok().getText());
            assertFalse(s.ok().isDisable());
            assertEquals("", s.warning());

            // The highlighted row is yours: Space unticks it, and nothing is left to encrypt for.
            s.press(s.search(), KeyCode.SPACE);
            assertTrue(s.ok().isDisable());
            assertFalse(s.chips().isVisible());
            s.press(s.search(), KeyCode.ENTER);
            assertTrue(s.accepted().isEmpty(), "Enter with nobody ticked does nothing");

            // Down moves the highlight to Dana; Space ticks her.
            s.press(s.search(), KeyCode.DOWN);
            assertEquals(DANA, s.list().getSelectionModel().getSelectedItem());
            s.press(s.search(), KeyCode.SPACE);
            assertEquals(tr("encryption.picker.encryptWithoutMe"), s.ok().getText());
            assertEquals(tr("encryption.picker.withoutMe"), s.warning());

            // A search narrows the list and highlights its first match; Zed is not verified.
            s.search().setText("zed");
            assertEquals(List.of(ZED), s.list().getItems());
            s.press(s.search(), KeyCode.SPACE);
            assertTrue(
                    s.warning().startsWith(tr("encryption.picker.unverified", ZED.name(), ZED.groupedFingerprint())));
            assertEquals(tr("encryption.picker.encryptWithoutMe"), s.ok().getText());
            s.search().setText("");

            // Tick yourself back with Space on the first row: two people, no warning about you.
            s.list().getSelectionModel().select(0);
            s.press(s.search(), KeyCode.SPACE);
            assertEquals(tr("encryption.picker.encryptFor", "3"), s.ok().getText());
            assertEquals(3, s.chips().getChildren().size());

            s.press(s.search(), KeyCode.ENTER);
            assertEquals(1, s.accepted().size());
            RecipientPicker.Choice c = s.accepted().getFirst();
            assertEquals(EncryptionState.Mode.PUBLIC_KEY, c.mode());
            assertEquals(List.of(DANA, ZED, ME), c.keys(), "in the order they were ticked");
            assertNull(c.passphrase());
            assertFalse(c.rememberForFolder());
            assertFalse(s.host().isShowing());
            assertEquals(0, s.cancels()[0], "an accepted card is not a cancel");
            s.stage().close();
        });
    }

    @Test
    void spaceOnTheSearchNeverTypesAndAnEmptySearchSelectsNothing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(request(
                    List.of(ME, DANA), Set.of(ME.fingerprint()), EncryptionState.Mode.PUBLIC_KEY, false, false, null));
            KeyEvent typed = new KeyEvent(KeyEvent.KEY_TYPED, " ", "", KeyCode.UNDEFINED, false, false, false, false);
            Event.fireEvent(s.search(), typed);
            assertEquals("", s.search().getText());
            s.search().setText("nobody at all");
            assertTrue(s.list().getItems().isEmpty());
            s.list().getSelectionModel().clearSelection();
            s.press(s.search(), KeyCode.SPACE);
            assertEquals(1, s.chips().getChildren().size(), "Space with no row highlighted ticks nothing");
            s.stage().close();
        });
    }

    @Test
    void clickingARowTogglesItAndTheChipCrossRemovesIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(
                    request(List.of(ME, DANA, GONE), Set.of(), EncryptionState.Mode.PUBLIC_KEY, false, false, null));
            List<ListCell<?>> cells = s.filledCells();
            assertEquals(3, cells.size());
            ListCell<?> gone =
                    cells.stream().filter(c -> c.getItem() == GONE).findFirst().orElseThrow();
            assertTrue(gone.getGraphic().getStyleClass().contains("encryption-key-unusable"));
            assertTrue(((CheckBox) gone.getGraphic().lookup(".check-box")).isDisable());
            Event.fireEvent(gone, click());
            assertTrue(s.ok().isDisable(), "a revoked key cannot be ticked");

            ListCell<?> dana =
                    cells.stream().filter(c -> c.getItem() == DANA).findFirst().orElseThrow();
            Event.fireEvent(dana, click());
            assertFalse(s.ok().isDisable());
            assertEquals(1, s.chips().getChildren().size());
            assertTrue(s.filledCells().stream()
                    .filter(c -> c.getItem() == DANA)
                    .map(c -> ((CheckBox) c.getGraphic().lookup(".check-box")).isSelected())
                    .findFirst()
                    .orElseThrow());

            Button cross = (Button) s.chips().lookup(".encryption-chip-remove");
            cross.fire();
            assertTrue(s.chips().getChildren().isEmpty());
            assertTrue(s.ok().isDisable());

            // Cancelling (the host hides without an accept) reports a cancel.
            s.host().hide();
            assertEquals(1, s.cancels()[0]);
            s.stage().close();
        });
    }

    @Test
    void manyTickedKeysShowCappedChipsAndAMoreLabel() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<GpgKey> keys = new ArrayList<>();
            Set<String> ticked = new LinkedHashSet<>();
            for (int i = 0; i < RecipientPicker.MAX_CHIPS + 5; i++) {
                GpgKey k = key(
                        String.format("%040X", i + 1), "Person " + i + " <p" + i + "@example.org>", 'f', "E", false);
                keys.add(k);
                ticked.add(k.fingerprint());
            }
            Shown s = show(request(keys, ticked, EncryptionState.Mode.PUBLIC_KEY, true, false, null));
            assertEquals(RecipientPicker.MAX_CHIPS + 1, s.chips().getChildren().size(), "the chips and a +5 label");
            assertNotNull(s.chips().lookup(".encryption-chip-more"));
            assertEquals(tr("encryption.picker.applyOnSave"), s.ok().getText());
            s.stage().close();
        });
    }

    @Test
    void thePassphraseSideChecksBothFieldsAndHandsThePassphraseBack() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(request(List.of(ME), Set.of(), EncryptionState.Mode.PASSPHRASE, false, true, "notes"));
            List<PasswordField> fields = s.passwords();
            assertEquals(2, fields.size());
            assertEquals(tr("encryption.picker.encryptWithPassphrase"), s.ok().getText());
            assertTrue(s.ok().isDisable(), "an empty passphrase is not acceptable");
            fields.get(0).setText("correct horse");
            fields.get(1).setText("correct");
            assertEquals(tr("encryption.picker.passphraseMismatch"), s.warning());
            assertTrue(s.ok().isDisable());
            fields.get(1).setText("correct horse");
            assertEquals("", s.warning());
            assertFalse(s.ok().isDisable());

            @SuppressWarnings("unchecked")
            ComboBox<Boolean> format = (ComboBox<Boolean>) s.card().lookup(".combo-box");
            assertNotNull(format, "a new file offers the format");
            assertEquals(tr("encryption.format.binary"), format.getConverter().toString(false));
            assertEquals(tr("encryption.format.armor"), format.getConverter().toString(true));
            assertNull(format.getConverter().fromString("x"));
            format.setValue(true);
            CheckBox remember = s.card().lookupAll(".check-box").stream()
                    .filter(n -> n instanceof CheckBox b
                            && b.getText().equals(tr("encryption.picker.rememberFolder", "notes")))
                    .map(CheckBox.class::cast)
                    .findFirst()
                    .orElseThrow();
            remember.setSelected(true);

            s.ok().fire();
            RecipientPicker.Choice c = s.accepted().getFirst();
            assertEquals(EncryptionState.Mode.PASSPHRASE, c.mode());
            assertArrayEquals("correct horse".toCharArray(), c.passphrase());
            assertTrue(c.armor());
            assertFalse(c.rememberForFolder(), "a folder remembers recipients, not a passphrase");
            assertTrue(c.keys().isEmpty());
            assertEquals("", fields.get(0).getText(), "the fields are wiped");
            s.stage().close();
        });
    }

    @Test
    void theModesSwitchAndOneSideIsAlwaysOn() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(request(
                    List.of(ME, DANA), Set.of(ME.fingerprint()), EncryptionState.Mode.PUBLIC_KEY, true, false, "docs"));
            ToggleButton keys = (ToggleButton) s.card().lookup(".left-pill");
            ToggleButton pass = (ToggleButton) s.card().lookup(".right-pill");
            assertTrue(keys.isSelected());
            keys.setSelected(false);
            assertTrue(keys.isSelected(), "unselecting the selected side selects it again");
            pass.setSelected(true);
            assertEquals(2, s.passwords().size());
            assertEquals(tr("encryption.picker.applyOnSave"), s.ok().getText());
            keys.setSelected(true);
            assertNotNull(s.search());
            CheckBox remember = s.card().lookupAll(".check-box").stream()
                    .filter(n -> n instanceof CheckBox b && !b.getText().isEmpty())
                    .map(CheckBox.class::cast)
                    .findFirst()
                    .orElseThrow();
            remember.setSelected(true);
            s.press(s.search(), KeyCode.ENTER);
            assertTrue(s.accepted().getFirst().rememberForFolder());
            assertEquals(List.of(ME), s.accepted().getFirst().keys());
            s.stage().close();
        });
    }

    @Test
    void enterOnAnotherButtonPressesThatButton() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(request(
                    List.of(ME), Set.of(ME.fingerprint()), EncryptionState.Mode.PUBLIC_KEY, false, false, null));
            s.press(s.button(tr("dialog.cancel")), KeyCode.ENTER);
            assertFalse(s.host().isShowing());
            assertTrue(s.accepted().isEmpty());
            assertEquals(1, s.cancels()[0]);
            s.stage().close();
        });
    }

    @Test
    void anEmptyKeyringOffersAPassphraseOrAnImport() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(request(List.of(GONE), Set.of(), EncryptionState.Mode.PUBLIC_KEY, false, false, null));
            assertNotNull(s.card().lookup(".encryption-empty"));
            assertNull(s.search(), "no search over a keyring with nothing usable");
            s.press(s.card(), KeyCode.SPACE); // nothing to tick: no failure
            s.button(tr("encryption.picker.usePassphrase")).fire();
            assertEquals(2, s.passwords().size());
            s.stage().close();

            Shown t = show(request(List.of(), Set.of(), EncryptionState.Mode.PUBLIC_KEY, false, false, null));
            t.button(tr("encryption.action.importKey")).fire();
            assertEquals(1, t.imports()[0]);
            assertFalse(t.host().isShowing());
            t.stage().close();
        });
    }

    @Test
    void theImportLinkHidesTheCardAndImports() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(request(List.of(ME), Set.of(), EncryptionState.Mode.PUBLIC_KEY, false, false, null));
            javafx.scene.control.Hyperlink link =
                    (javafx.scene.control.Hyperlink) s.card().lookup(".hyperlink");
            link.fire();
            assertEquals(1, s.imports()[0]);
            assertFalse(s.host().isShowing());
            s.stage().close();
        });
    }
}
