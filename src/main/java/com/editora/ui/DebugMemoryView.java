package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
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
 * variable's View Memory). ◀ and ▶ page back and forth; it is read again at every stop.
 */
final class DebugMemoryView extends VBox {

    /** Bytes per row, as hex editors show them. */
    static final int ROW = 16;

    /** Bytes read at a time: one page of the view. */
    static final int PAGE = 256;

    private final TextField where = new TextField();
    private final ListView<String> rows = new ListView<>();
    private final Label placeholder = new Label();
    private Consumer<String> onGo = text -> {};
    private IntConsumer onPage = direction -> {};

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
        // Its right-click menu: Copy, the one thing to do to a row (MENU-05).
        ListRowCommands.install(rows, ListRowCommands.copyRow(rows, () -> ListRowCommands.selectedText(rows, r -> r)));
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
        rows.getItems().setAll(lines);
        if (lines.isEmpty()) {
            setMessage(tr("debugpanel.memory.unreadable", block.address()));
        }
    }

    /** Clears the view, saying {@code message} instead. */
    void setMessage(String message) {
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
}
