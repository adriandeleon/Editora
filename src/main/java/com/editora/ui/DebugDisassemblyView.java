package com.editora.ui;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import com.editora.dap.DapModels;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Debug window's Disassembly view (VS Code's Disassembly editor, IntelliJ's Disassembly window): the machine
 * instructions around the selected frame's instruction pointer, the one it is at marked, with the source line each
 * run of instructions was compiled from. Double-clicking one opens that line. While it has the keyboard, Step Over
 * and Step Into step one instruction (as VS Code does when its Disassembly view is focused).
 *
 * <p>Where the adapter takes them (CodeLLDB, GDB, Delve), an instruction breakpoint goes on an instruction by a click
 * in the margin before its address, Toggle Breakpoint (F9) or the row's right-click menu: a red dot, hollow while
 * the adapter could not set it. They are the session's: an address means nothing in the next run.
 */
final class DebugDisassemblyView extends VBox {

    private final ListView<DapModels.Instruction> list = new ListView<>();
    private final Label placeholder = new Label();
    private String current = "";
    private BiConsumer<Path, Integer> onOpenSource = (file, line) -> {};
    /** The instruction breakpoints' addresses, as numbers. */
    private final Set<Long> breakpoints = new HashSet<>();

    /** Those of {@link #breakpoints} the adapter could not set. */
    private final Set<Long> unverified = new HashSet<>();

    /** The marked instructions whose breakpoint asks a condition or a hit count first: drawn as the gutter does. */
    private final Set<Long> conditional = new HashSet<>();

    /** What Edit Breakpoint… does to an instruction's breakpoint (its condition, its hit count). */
    private Consumer<DapModels.Instruction> onEditBreakpoint = i -> {};

    private Consumer<DapModels.Instruction> onToggleBreakpoint = i -> {};
    private BooleanSupplier breakpointsTaken = () -> false;

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
                        },
                        "row.unavailable.noSourceFile"),
                ListRowCommands.enabledWhen(
                        ListRowCommands.row(
                                list,
                                "debug.toggleBreakpoint",
                                null,
                                BreakpointMenus.breakpointGlyph(),
                                this::toggleSelected),
                        () -> breakpointsTaken.getAsBoolean(),
                        "row.unavailable.noInstructionBreakpoints"),
                ListRowCommands.enabledWhen(
                        ListRowCommands.row(list, "debug.editBreakpoint", null, null, this::editSelected),
                        () -> {
                            DapModels.Instruction i = list.getSelectionModel().getSelectedItem();
                            return i != null && hasBreakpoint(i.address());
                        },
                        "row.unavailable.noInstructionBreakpoint"),
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

    /** Toggles the selected instruction's breakpoint, as F9 does in the view; false when none is selected. */
    boolean toggleSelected() {
        DapModels.Instruction i = list.getSelectionModel().getSelectedItem();
        if (i == null) {
            return false;
        }
        onToggleBreakpoint.accept(i); // says so when the adapter takes none
        return true;
    }

    /**
     * What toggling an instruction's breakpoint does ({@code onToggle}), and whether the session's adapter takes
     * instruction breakpoints ({@code taken}): without, the row is grayed out, and the margin and F9 say so.
     */
    void setOnToggleBreakpoint(Consumer<DapModels.Instruction> onToggle, BooleanSupplier taken) {
        this.onToggleBreakpoint = onToggle;
        this.breakpointsTaken = taken;
    }

    /** Edits the selected instruction's breakpoint; false when it has none. */
    boolean editSelected() {
        DapModels.Instruction i = list.getSelectionModel().getSelectedItem();
        if (i == null || !hasBreakpoint(i.address())) {
            return false;
        }
        onEditBreakpoint.accept(i);
        return true;
    }

    /** What Edit Breakpoint… on a row with a breakpoint does. */
    void setOnEditBreakpoint(Consumer<DapModels.Instruction> onEdit) {
        this.onEditBreakpoint = onEdit;
    }

    /** Selects the shown instruction at {@code address} (a Breakpoints list row); false when it is not shown. */
    boolean selectAddress(String address) {
        Long wanted = parseAddress(address);
        for (int i = 0; i < list.getItems().size(); i++) {
            Long at = parseAddress(list.getItems().get(i).address());
            if (wanted != null && wanted.equals(at)) {
                list.getSelectionModel().select(i);
                list.scrollTo(Math.max(0, i - 3));
                return true;
            }
        }
        return false;
    }

    /**
     * Marks the instructions at {@code addresses}, hollow those at {@code notSet} (the adapter could not set them), in
     * the conditional colour those at {@code withConditions}.
     */
    void setBreakpoints(Collection<String> addresses, Collection<String> notSet, Collection<String> withConditions) {
        conditional.clear();
        for (String a : withConditions) {
            Long n = parseAddress(a);
            if (n != null) {
                conditional.add(n);
            }
        }
        setBreakpoints(addresses, notSet);
    }

    /** Marks the instructions at {@code addresses}, hollow those at {@code notSet} (the adapter could not set them). */
    void setBreakpoints(Collection<String> addresses, Collection<String> notSet) {
        breakpoints.clear();
        unverified.clear();
        for (String a : addresses) {
            Long n = parseAddress(a);
            if (n != null) {
                breakpoints.add(n);
            }
        }
        for (String a : notSet) {
            Long n = parseAddress(a);
            if (n != null) {
                unverified.add(n);
            }
        }
        list.refresh();
    }

    /** Whether the instruction at {@code address} has a breakpoint marked. */
    boolean hasBreakpoint(String address) {
        Long n = parseAddress(address);
        return n != null && breakpoints.contains(n);
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
        return com.editora.dap.DapManager.parseAddress(address);
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
        private final Circle dot = new Circle();
        private final StackPane mark = new StackPane(dot);
        private final Label address = new Label();
        private final Label text = new Label();
        private final Label symbol = new Label();
        private final Label where = new Label();
        private final Region gap = new Region();
        private final HBox row = UiZoom.spacing(new HBox(mark, address, text, gap, symbol, where), 10);

        InstructionCell() {
            address.getStyleClass().add("debug-disassembly-address");
            text.getStyleClass().add("debug-disassembly-text");
            symbol.getStyleClass().add("debug-disassembly-symbol");
            where.getStyleClass().add("debug-disassembly-source");
            HBox.setHgrow(gap, Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
            // The breakpoint margin: a click there toggles the instruction's breakpoint, as the editor's gutter does.
            mark.getStyleClass().add("debug-disassembly-margin");
            UiZoom.follow(mark, () -> {
                dot.setRadius(UiZoom.px(4));
                mark.setMinWidth(UiZoom.px(12));
                mark.setPrefWidth(UiZoom.px(12));
            });
            dot.getStyleClass().add("debug-disassembly-breakpoint");
            mark.setOnMouseClicked(e -> {
                DapModels.Instruction item = getItem();
                if (e.getButton() == MouseButton.PRIMARY && item != null) {
                    onToggleBreakpoint.accept(item);
                    e.consume();
                }
            });
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
            Long at = parseAddress(item.address());
            dot.setVisible(at != null && breakpoints.contains(at));
            dot.getStyleClass()
                    .removeAll("debug-disassembly-breakpoint-unverified", "debug-disassembly-breakpoint-conditional");
            if (at != null && conditional.contains(at)) {
                dot.getStyleClass().add("debug-disassembly-breakpoint-conditional");
            }
            if (at != null && unverified.contains(at)) {
                dot.getStyleClass().add("debug-disassembly-breakpoint-unverified");
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
