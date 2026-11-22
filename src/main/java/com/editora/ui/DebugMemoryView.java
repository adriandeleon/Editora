package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.editora.dap.DapModels;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Debug window's Memory view (VS Code's hex editor on a variable, Visual Studio's Memory window): the program's
 * memory as rows of 16 bytes — the address, the bytes in hex, and as text — from an address or an expression (a
 * variable's View Memory). ◀ and ▶ page back and forth; it is read again at every stop. A row's bytes can be changed
 * (Edit Bytes…: a double-click, F2) where the debugger writes memory.
 */
final class DebugMemoryView extends VBox {

    /** Bytes per row, as hex editors show them. */
    static final int ROW = 16;

    /** The command that edits the selected row's bytes. */
    static final String EDIT = "debug.memoryEditBytes";

    /** Bytes read at a time: one page of the view. */
    static final int PAGE = 256;

    private final TextField where = new TextField();
    private final ListView<String> rows = new ListView<>();
    private final Label placeholder = new Label();
    private Consumer<String> onGo = text -> {};
    private IntConsumer onPage = direction -> {};
    private IntConsumer onEdit = row -> {};
    private BooleanSupplier writable = () -> false;

    /** What the view shows: null while it says something instead. */
    private DapModels.@Nullable MemoryBlock block;

    DebugMemoryView() {
        getStyleClass().add("debug-memory");
        where.setPromptText(tr("debugpanel.memory.prompt"));
        where.getStyleClass().addAll("debug-eval", "debug-memory-address");
        where.setOnAction(e -> onGo.accept(where.getText().strip()));
        Button back = new Button("◀");
        CommandTips.name(back, "debug.memoryPreviousPage");
        back.setOnAction(e -> page(-1));
        Button next = new Button("▶");
        CommandTips.name(next, "debug.memoryNextPage");
        next.setOnAction(e -> page(1));
        for (Button b : List.of(back, next)) {
            b.getStyleClass().add("small");
            b.setFocusTraversable(false);
        }
        HBox top = UiZoom.spacing(new HBox(where, back, next), 4);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(where, Priority.ALWAYS);
        rows.getStyleClass().addAll("debug-stack", "debug-memory-rows");
        placeholder.setWrapText(true);
        placeholder.getStyleClass().add("debug-empty-note");
        rows.setPlaceholder(placeholder);
        rows.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                editSelected();
            }
        });
        rows.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F2 && editSelected()) {
                e.consume();
            }
        });
        // Its right-click menu: what a double-click does, and Copy (MENU-05).
        ListRowCommands.install(
                rows,
                ListRowCommands.enabledWhen(
                        ListRowCommands.row(rows, EDIT, null, null, this::editSelected),
                        () -> writable.getAsBoolean(),
                        "row.unavailable.noMemoryWrite"),
                ListRowCommands.copyRow(rows, () -> ListRowCommands.selectedText(rows, r -> r)));
        VBox.setVgrow(rows, Priority.ALWAYS);
        UiZoom.spacing(this, 2);
        getChildren().addAll(top, rows);
        setMessage(tr("debugpanel.memory.idle"));
    }

    /** What Enter in the address field asks for: the text typed (an address or an expression). */
    void setOnGo(Consumer<String> onGo) {
        this.onGo = onGo;
    }

    /** What ◀ (-1) and ▶ (+1) ask for: the page before or after. */
    void setOnPage(IntConsumer onPage) {
        this.onPage = onPage;
    }

    /** ◀ ({@code -1}, {@code debug.memoryPreviousPage}) or ▶ ({@code +1}, {@code debug.memoryNextPage}). */
    void page(int direction) {
        onPage.accept(direction);
    }

    /**
     * What Edit Bytes… asks for: the row to change (its index), when {@code writable} (the debugger writes memory, and
     * the row's menu has Edit Bytes… enabled).
     */
    void setOnEdit(IntConsumer onEdit, BooleanSupplier writable) {
        this.onEdit = onEdit;
        this.writable = writable;
    }

    /** Edit Bytes… on the selected row; false when no row is selected. */
    boolean editSelected() {
        int row = rows.getSelectionModel().getSelectedIndex();
        if (block == null || row < 0 || row * ROW >= block.data().length) {
            return false;
        }
        onEdit.accept(row);
        return true;
    }

    /** The bytes shown, or null while the view says something instead. */
    DapModels.@Nullable MemoryBlock block() {
        return block;
    }

    /** Puts {@code text} in the address field (a variable's expression, for View Memory). */
    void setAddressText(String text) {
        where.setText(text);
    }

    String addressText() {
        return where.getText().strip();
    }

    /** Shows {@code block}. */
    void show(DapModels.MemoryBlock block) {
        List<String> lines = rows(block.address(), block.data(), block.unreadable());
        int selected = rows.getSelectionModel().getSelectedIndex();
        rows.getItems().setAll(lines);
        if (lines.isEmpty()) {
            setMessage(tr("debugpanel.memory.unreadable", block.address()));
        } else {
            this.block = block;
            if (selected >= 0 && selected < lines.size()) {
                rows.getSelectionModel().select(selected); // read again after an edit: the row stays selected
            }
        }
    }

    /** Clears the view, saying {@code message} instead. */
    void setMessage(String message) {
        block = null;
        rows.getItems().clear();
        placeholder.setText(message);
    }

    ListView<String> rowList() {
        return rows;
    }

    TextField addressField() {
        return where;
    }

    /**
     * {@code data} read from {@code address} as hex-editor rows: {@code 0x00007ffc0a10  48 65 6c 6c 6f 00 …  Hello.},
     * the address counting up 16 a row when it is a number (else the first row's alone); {@code unreadable} bytes after
     * them as {@code ??}. Pure.
     */
    static List<String> rows(String address, byte[] data, int unreadable) {
        List<String> out = new ArrayList<>();
        Long start = DebugDisassemblyView.parseAddress(address);
        int total = data.length + Math.max(0, unreadable);
        for (int at = 0; at < total; at += ROW) {
            StringBuilder hex = new StringBuilder();
            StringBuilder text = new StringBuilder();
            for (int i = at; i < at + ROW; i++) {
                if (i < data.length) {
                    int b = data[i] & 0xff;
                    hex.append(String.format("%02x ", b));
                    text.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
                } else if (i < total) {
                    hex.append("?? ");
                    text.append(' ');
                } else {
                    hex.append("   ");
                }
                if (i == at + 7) {
                    hex.append(' ');
                }
            }
            String where = start != null ? String.format("0x%012x", start + at) : at == 0 ? address : "+" + at;
            out.add(where + "  " + hex + " " + text);
        }
        return out;
    }

    /** The memory reference typed: an address ({@code 0x…}) as it is, else null (an expression to evaluate). Pure. */
    static @Nullable String literalAddress(String text) {
        return DebugDisassemblyView.parseAddress(text) != null && text.strip().startsWith("0x") ? text.strip() : null;
    }

    /** The address of byte {@code at} of {@code block}, as its rows show it. Pure. */
    static String addressOf(DapModels.MemoryBlock block, int at) {
        Long start = DebugDisassemblyView.parseAddress(block.address());
        return start != null ? String.format("0x%x", start + at) : block.address() + "+" + at;
    }

    /** Row {@code row}'s readable bytes of {@code data} in hex, as Edit Bytes… starts from: {@code 48 65 6c 6c 6f}. */
    static String rowHex(byte[] data, int row) {
        StringBuilder hex = new StringBuilder();
        for (int i = row * ROW; i < Math.min(data.length, (row + 1) * ROW); i++) {
            hex.append(hex.isEmpty() ? "" : " ").append(String.format("%02x", data[i] & 0xff));
        }
        return hex.toString();
    }

    /**
     * The bytes typed in Edit Bytes…: hex, two digits a byte, spaces optional ({@code 48 65 6c}, {@code 48656c},
     * {@code 0x48}), or text in double quotes ({@code "Hello"}, its UTF-8 bytes); null when it is neither or empty.
     * Pure.
     */
    static byte @Nullable [] parseBytes(String text) {
        String t = text.strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            byte[] bytes = t.substring(1, t.length() - 1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return bytes.length == 0 ? null : bytes;
        }
        StringBuilder digits = new StringBuilder();
        for (String token : t.split("[\\s,]+")) {
            String d = token.startsWith("0x") || token.startsWith("0X") ? token.substring(2) : token;
            if (d.isEmpty() && !token.isEmpty()) {
                return null; // a bare 0x
            }
            if (token.length() > 2 && token.length() != 4 && (token.startsWith("0x") || token.startsWith("0X"))) {
                return null; // 0x is one byte's prefix: 0x123 is no byte
            }
            digits.append(d);
        }
        if (digits.isEmpty()
                || digits.length() % 2 != 0
                || !digits.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return null;
        }
        byte[] out = new byte[digits.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(digits.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** What Edit Bytes… writes: {@code bytes} at byte {@code at} of the page. */
    record Edit(int at, byte[] bytes) {}

    /**
     * What to write for {@code typed} put over {@code data} from byte {@code from}: the bytes from the first that
     * differs to the last, or null when none does. {@code typed} must fit in {@code data} from there. Pure.
     */
    static @Nullable Edit edit(byte[] data, int from, byte[] typed) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < typed.length; i++) {
            if (data[from + i] != typed[i]) {
                first = first < 0 ? i : first;
                last = i;
            }
        }
        return first < 0 ? null : new Edit(from + first, java.util.Arrays.copyOfRange(typed, first, last + 1));
    }
}
