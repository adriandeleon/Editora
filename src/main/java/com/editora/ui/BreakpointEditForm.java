package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import com.editora.config.Breakpoint;
import com.editora.editor.EditorBuffer;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Debug: Edit Breakpoint's form on the caret line — condition, log message, hit count, the breakpoint it waits for, its
 * group, its switches and filters — shown over the editor; the breakpoint is made, or changed, only when the form is
 * accepted. Moved out of {@link DebugCoordinator}, which keeps the command.
 */
final class BreakpointEditForm {

    private BreakpointEditForm() {}

    /** Shows the form for {@code line} of {@code b}. FX thread. */
    static void show(
            OverlayHost overlayHost,
            EditorBuffer b,
            int line,
            DebugCompletion.Source conditionSource,
            DebugCompletion.Source logSource,
            BreakpointGroups groups,
            TriggeredBreakpoints triggered,
            List<String> marks) {
        var mgr = b.getBreakpointManager();
        // A line without a breakpoint gets one only when the form is accepted: creating it up front left it
        // behind — saved, and sent to a live session — when the form was cancelled.
        Breakpoint existing = mgr.get(line);
        boolean existed = existing != null;
        Breakpoint bp = existing != null
                ? existing
                : Breakpoint.plain(
                        line, b.getArea().getParagraph(line).getText().strip());

        TextField condition = new TextField(bp.condition());
        condition.setPrefColumnCount(32);
        TextField logMessage = new TextField(bp.logMessage());
        logMessage.setPrefColumnCount(32);
        // Completion in the stopped frame (follow-up 5): the whole condition, and the {expression} of a log message.
        DebugCompletion.attach(condition, conditionSource);
        DebugCompletion.attach(logMessage, logSource);
        TextField hitCondition = new TextField(bp.hitCondition());
        hitCondition.setPrefColumnCount(32);
        hitCondition.setPromptText(BreakpointFormText.hitConditionPrompt(b.getLanguage()));
        CheckBox enabled = new CheckBox(tr("dialog.debug.breakpointEnabled"));
        enabled.setSelected(bp.enabled());
        CheckBox temporary = new CheckBox(tr("dialog.debug.breakpointTemporary"));
        temporary.setSelected(bp.temporary());
        CheckBox recording = new CheckBox(tr("dialog.debug.breakpointRecording"));
        recording.setSelected(bp.recording());
        // Wait for: another breakpoint this one waits for in each session (a triggered breakpoint).
        javafx.scene.control.ComboBox<BreakpointsPicker.Entry> waitFor = waitForChoice(b, line, bp, triggered);
        javafx.scene.control.ComboBox<String> group = groups.groupField(bp.group());
        // Filters (IntelliJ's): where a hit counts — a thread, a caller, a marked object. Checked by Editora.
        TextField threadFilter = new TextField(bp.filters().thread());
        threadFilter.setPromptText(tr("dialog.debug.threadFilterPrompt"));
        TextField callerFilter = new TextField(bp.filters().caller());
        callerFilter.setPromptText(tr("dialog.debug.callerFilterPrompt"));
        javafx.scene.control.ComboBox<String> instanceFilter = new javafx.scene.control.ComboBox<>();
        instanceFilter.setEditable(true);
        instanceFilter.getItems().setAll(marks);
        instanceFilter.setValue(bp.filters().instance());
        instanceFilter.setPromptText(tr("dialog.debug.instanceFilterPrompt"));
        instanceFilter.setMaxWidth(Double.MAX_VALUE);
        for (javafx.scene.control.Control c : List.of(threadFilter, callerFilter, instanceFilter)) {
            javafx.scene.control.Tooltip hint = com.editora.editor.ControlNames.tooltip(tr("dialog.debug.filtersHint"));
            hint.setWrapText(true);
            hint.setMaxWidth(UiZoom.px(360));
            c.setTooltip(hint);
        }

        GridPane grid = Dialogs.labelColumn(new GridPane());
        UiZoom.gaps(grid, 8, 8);
        // The line the breakpoint is on: the card covers the editor, often that very line (review 2 C106).
        Label lineText = new Label(
                BreakpointFormText.lineText(b.getArea().getParagraph(line).getText(), 64));
        lineText.getStyleClass().add("debug-breakpoint-line");
        grid.add(lineText, 0, 0, 2, 1);
        grid.add(new Label(tr("dialog.debug.conditionLabel")), 0, 1);
        grid.add(condition, 1, 1);
        grid.add(new Label(tr("dialog.debug.logMessageLabel")), 0, 2);
        grid.add(logMessage, 1, 2);
        grid.add(new Label(tr("dialog.debug.hitConditionLabel")), 0, 3);
        grid.add(hitCondition, 1, 3);
        grid.add(new Label(tr("dialog.debug.triggeredByLabel")), 0, 4);
        grid.add(waitFor, 1, 4);
        grid.add(new Label(tr("dialog.debug.groupLabel")), 0, 5);
        grid.add(group, 1, 5);
        // The filters show on a toggle beside the switches, on when the breakpoint has one: most breakpoints have
        // none, and the form keeps its height (DebugReviewCWindowFxTest).
        GridPane filterGrid = Dialogs.labelColumn(new GridPane());
        UiZoom.gaps(filterGrid, 8, 8);
        filterGrid.add(new Label(tr("dialog.debug.threadFilterLabel")), 0, 0);
        filterGrid.add(threadFilter, 1, 0);
        filterGrid.add(new Label(tr("dialog.debug.callerFilterLabel")), 0, 1);
        filterGrid.add(callerFilter, 1, 1);
        filterGrid.add(new Label(tr("dialog.debug.instanceFilterLabel")), 0, 2);
        filterGrid.add(instanceFilter, 1, 2);
        GridPane.setHgrow(threadFilter, Priority.ALWAYS);
        GridPane.setHgrow(callerFilter, Priority.ALWAYS);
        GridPane.setHgrow(instanceFilter, Priority.ALWAYS);
        // A link, not a button: as tall as the switches beside it, so the form keeps its height.
        javafx.scene.control.Hyperlink filtersToggle = new javafx.scene.control.Hyperlink();
        filtersToggle.getStyleClass().add("debug-breakpoint-filters-toggle");
        filterGrid.setVisible(!bp.filters().none());
        filterGrid.managedProperty().bind(filterGrid.visibleProperty());
        Runnable filtersText = () ->
                filtersToggle.setText(tr("dialog.debug.filters") + (filterGrid.isVisible() ? " \u25BE" : " \u25B8"));
        filtersText.run();
        filtersToggle.setOnAction(e -> {
            filterGrid.setVisible(!filterGrid.isVisible());
            filtersToggle.setVisited(false);
            filtersText.run();
        });
        // In the switches' own cell: hidden, the filters add no row (and no row gap) to the form.
        javafx.scene.layout.VBox switches = UiZoom.spacing(
                new javafx.scene.layout.VBox(
                        UiZoom.spacing(new javafx.scene.layout.HBox(enabled, temporary, recording, filtersToggle), 16),
                        filterGrid),
                8);
        grid.add(switches, 1, 6);
        grid.add(note(tr("dialog.debug.logMessageHint")), 1, 7);
        GridPane.setHgrow(condition, Priority.ALWAYS);
        GridPane.setHgrow(logMessage, Priority.ALWAYS);

        OverlayInput.show(
                overlayHost,
                BreakpointFormText.title(b.getPath(), line),
                grid,
                condition,
                tr("dialog.ok"),
                null,
                () -> {
                    if (!existed && !mgr.isBreakpoint(line)) {
                        b.toggleBreakpoint(line);
                    }
                    mgr.setCondition(line, condition.getText().trim());
                    mgr.setLogMessage(line, logMessage.getText().trim());
                    mgr.setHitCondition(line, hitCondition.getText().trim());
                    mgr.setEnabled(line, enabled.isSelected());
                    mgr.setTemporary(line, temporary.isSelected());
                    mgr.setRecording(line, recording.isSelected());
                    mgr.setGroup(line, BreakpointGroups.groupIn(group));
                    String instance = instanceFilter.getEditor() == null
                            ? instanceFilter.getValue()
                            : instanceFilter.getEditor().getText();
                    mgr.setFilters(
                            line,
                            new com.editora.config.StopFilters(
                                    threadFilter.getText(), callerFilter.getText(), instance));
                    BreakpointsPicker.Entry trigger = waitFor.getValue();
                    if (trigger != null && trigger.line() < 0) {
                        trigger = null; // "no other breakpoint"
                    }
                    Breakpoint now = mgr.get(line);
                    String was = now == null ? "" : now.triggeredBy();
                    if (now != null
                            && !(trigger == null
                                    ? was.isEmpty()
                                    : was.equals(trigger.breakpoint().id()))) {
                        triggered.link(b, line, trigger);
                    }
                },
                null,
                false);
    }

    /** Debug: Add Logpoint's form for {@code line} of {@code b}: OK waits for a message. FX thread. */
    static void showLogpoint(
            OverlayHost overlayHost,
            EditorBuffer b,
            int line,
            DebugCompletion.Source logSource,
            java.util.function.Consumer<String> status) {
        var mgr = b.getBreakpointManager();
        Breakpoint existing = mgr.get(line);
        TextField message = new TextField(existing == null ? "" : existing.logMessage());
        message.setPrefColumnCount(32);
        message.setPromptText(tr("dialog.debug.logpointPrompt"));
        DebugCompletion.attach(message, logSource); // the {expression}s, in the stopped frame
        Label lineText = new Label(
                BreakpointFormText.lineText(b.getArea().getParagraph(line).getText(), 64));
        lineText.getStyleClass().add("debug-breakpoint-line");
        GridPane grid = Dialogs.labelColumn(new GridPane());
        UiZoom.gaps(grid, 8, 8);
        grid.add(lineText, 0, 0, 2, 1);
        grid.add(new Label(tr("dialog.debug.logMessageLabel")), 0, 1);
        grid.add(message, 1, 1);
        grid.add(note(tr("dialog.debug.logpointHint")), 1, 2);
        GridPane.setHgrow(message, Priority.ALWAYS);
        // Nothing to log is not a logpoint: OK waits for a message rather than leaving a breakpoint that stops.
        var hasMessage = javafx.beans.binding.Bindings.createBooleanBinding(
                () -> !message.getText().isBlank(), message.textProperty());
        OverlayInput.show(
                overlayHost,
                BreakpointFormText.logpointTitle(b.getPath(), line),
                grid,
                message,
                tr("dialog.ok"),
                hasMessage,
                () -> {
                    String text = message.getText().trim();
                    if (text.isEmpty()) {
                        return;
                    }
                    if (!mgr.isBreakpoint(line)) {
                        b.toggleBreakpoint(line);
                    }
                    mgr.setLogMessage(line, text);
                    b.refreshGutterLine(line);
                    status.accept(tr("status.debug.logpointAdded", line + 1));
                },
                null,
                false);
    }

    /**
     * A small muted hint label under a form field, wrapped at a fixed width. Left at its own preferred width a
     * wrapping label asks for its whole text on one line, and the overlay card hugs its content, so the hint
     * stretched the form across the window and squeezed the field labels to an ellipsis. (A width bound to the
     * field's instead changes mid-layout and leaves the card at a stale height.)
     */
    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-note");
        l.setWrapText(true);
        UiZoom.prefWidth(l, 420);
        return l;
    }

    /**
     * The edit form's Wait for choice: no other breakpoint (a row with line -1), then each breakpoint the one on
     * {@code line} of {@code b} ({@code bp}) can wait for, with the one it waits for chosen.
     */
    private static javafx.scene.control.ComboBox<BreakpointsPicker.Entry> waitForChoice(
            EditorBuffer b, int line, Breakpoint bp, TriggeredBreakpoints triggered) {
        Path file = b.getPath();
        List<BreakpointsPicker.Entry> all = triggered.entries();
        List<BreakpointsPicker.Entry> choices = file == null
                ? List.of()
                : TriggeredBreakpoints.choices(
                        DebugCoordinator.key(file),
                        line + b.getBreakpointManager().regionFirstLine(),
                        bp.id(),
                        all);
        BreakpointsPicker.Entry none = new BreakpointsPicker.Entry(Path.of(""), "", -1, bp, null);
        javafx.scene.control.ComboBox<BreakpointsPicker.Entry> combo = new javafx.scene.control.ComboBox<>();
        combo.getItems().add(none);
        combo.getItems().addAll(choices);
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(BreakpointsPicker.@Nullable Entry e) {
                return e == null || e.line() < 0 ? tr("dialog.debug.triggeredByNone") : BreakpointsPicker.label(e);
            }

            @Override
            public BreakpointsPicker.@Nullable Entry fromString(String text) {
                return null; // not editable
            }
        });
        combo.setValue(choices.stream()
                .filter(e -> bp.isTriggered()
                        && bp.triggeredBy().equals(e.breakpoint().id()))
                .findFirst()
                .orElse(none));
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
    }
}
