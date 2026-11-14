package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.dap.DapModels;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Debug window's Disassembly view (VS Code's Disassembly editor, IntelliJ's Disassembly window): the machine
 * instructions around the selected frame's instruction pointer, the one it is at marked, with the source line each
 * run of instructions was compiled from. Double-clicking one opens that line. While it has the keyboard, Step Over
 * and Step Into step one instruction (as VS Code does when its Disassembly view is focused).
 */
final class DebugDisassemblyView extends VBox {

    private final ListView<DapModels.Instruction> list = new ListView<>();
    private final Label placeholder = new Label();
    private String current = "";
    private BiConsumer<Path, Integer> onOpenSource = (file, line) -> {};

    DebugDisassemblyView() {
        getStyleClass().add("debug-disassembly");
        list.getStyleClass().addAll("debug-stack", "debug-disassembly-list");
        placeholder.setWrapText(true);
        placeholder.getStyleClass().add("debug-empty-note");
        list.setPlaceholder(placeholder);
        list.setCellFactory(v -> new InstructionCell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });
        // Its right-click menu: what a double-click does, and Copy (MENU-05).
        ListRowCommands.install(
                list,
                ListRowCommands.enabledWhen(
                        ListRowCommands.row(list, ListRowCommands.OPEN, null, Icons.openInTab(), this::openSelected),
                        () -> {
                            DapModels.Instruction i = list.getSelectionModel().getSelectedItem();
                            return i != null && i.file() != null;
                        }),
                ListRowCommands.copyRow(list, () -> ListRowCommands.selectedText(list, DebugDisassemblyView::rowText)));
        VBox.setVgrow(list, Priority.ALWAYS);
        getChildren().add(list);
        setMessage(tr("debugpanel.disassembly.idle"));
    }

    /** Opens the selected instruction's source line, as a double-click does; false when it has none. */
    boolean openSelected() {
        DapModels.Instruction i = list.getSelectionModel().getSelectedItem();
        if (i == null || i.file() == null) {
            return false;
        }
        onOpenSource.accept(i.file(), i.line());
        return true;
    }

    /** An instruction as Copy gives it: its address, its text and its symbol. Pure. */
    static String rowText(DapModels.Instruction i) {
        String symbol = i.symbol().isBlank() ? "" : "  " + i.symbol();
        return i.address() + "  " + i.text() + symbol;
    }

    /** Where double-clicking an instruction goes: its source file and 0-based line. */
    void setOnOpenSource(BiConsumer<Path, Integer> onOpenSource) {
        this.onOpenSource = onOpenSource;
    }

    /** Shows {@code instructions}, the one at {@code currentAddress} marked, selected and in sight. */
    void show(List<DapModels.Instruction> instructions, String currentAddress) {
        current = currentAddress == null ? "" : currentAddress;
        list.getItems().setAll(instructions);
        int at = indexOf(instructions, current);
        if (at >= 0) {
            list.getSelectionModel().select(at);
            list.scrollTo(Math.max(0, at - 5));
        }
        if (instructions.isEmpty()) {
            setMessage(tr("debugpanel.disassembly.none"));
        }
    }

    /** Clears the view, saying {@code message} instead. */
    void setMessage(String message) {
        list.getItems().clear();
        current = "";
        placeholder.setText(message);
    }

    /** The row of the instruction at {@code address}, compared as numbers ({@code 0x00ab} is {@code 0xab}); -1. Pure. */
    static int indexOf(List<DapModels.Instruction> instructions, String address) {
        Long wanted = parseAddress(address);
        for (int i = 0; i < instructions.size(); i++) {
            String a = instructions.get(i).address();
            Long here = parseAddress(a);
            if (wanted != null ? wanted.equals(here) : a.equals(address)) {
                return i;
            }
        }
        return -1;
    }

    /** {@code 0x…} or decimal as a number; null when it is neither. Pure. */
    static @Nullable Long parseAddress(@Nullable String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String a = address.strip();
        try {
            return a.startsWith("0x") || a.startsWith("0X")
                    ? Long.parseUnsignedLong(a.substring(2), 16)
                    : Long.parseUnsignedLong(a);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The source place shown beside {@code instructions.get(index)}: {@code main.c:12} where the source line changes
     * from the instruction before, else {@code ""}. Pure.
     */
    static String sourceLabel(List<DapModels.Instruction> instructions, int index) {
        DapModels.Instruction i = instructions.get(index);
        if (i.file() == null || i.line() < 0) {
            return "";
        }
        if (index > 0) {
            DapModels.Instruction before = instructions.get(index - 1);
            if (i.file().equals(before.file()) && i.line() == before.line()) {
                return "";
            }
        }
        return i.file().getFileName() + ":" + (i.line() + 1);
    }

    ListView<DapModels.Instruction> list() {
        return list;
    }

    /** Whether the keyboard is in the view: steps go one instruction at a time then. */
    boolean hasFocus() {
        return list.isFocused();
    }

    private final class InstructionCell extends ListCell<DapModels.Instruction> {
        private final Label address = new Label();
        private final Label text = new Label();
        private final Label symbol = new Label();
        private final Label where = new Label();
        private final Region gap = new Region();
        private final HBox row = UiZoom.spacing(new HBox(address, text, gap, symbol, where), 10);

        InstructionCell() {
            address.getStyleClass().add("debug-disassembly-address");
            text.getStyleClass().add("debug-disassembly-text");
            symbol.getStyleClass().add("debug-disassembly-symbol");
            where.getStyleClass().add("debug-disassembly-source");
            HBox.setHgrow(gap, Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(DapModels.@Nullable Instruction item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("debug-disassembly-current");
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            address.setText(item.address());
            text.setText(item.text());
            symbol.setText(item.symbol());
            where.setText(sourceLabel(getListView().getItems(), getIndex()));
            if (!current.isEmpty() && indexOf(List.of(item), current) == 0) {
                getStyleClass().add("debug-disassembly-current");
            }
            setText(null);
            setGraphic(row);
        }
    }
}
