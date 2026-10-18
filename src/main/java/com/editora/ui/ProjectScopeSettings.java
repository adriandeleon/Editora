package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import com.editora.config.ProjectSettings;
import com.editora.config.ProjectSettingsFile;

import static com.editora.i18n.Messages.tr;

/**
 * The Settings window's <b>User / Project</b> switch and what it changes (F4).
 *
 * <p>In a project window a bar at the top of Settings chooses whose settings the page edits. In <b>User</b> mode
 * every row edits the user's settings, as always, and a row the project sets says "Overridden by this
 * project". In <b>Project</b> mode only the project-scopable rows can be changed: each shows the project's value
 * where its {@code .editora/settings.json} sets one (else the user's, as a starting point), and a change is
 * written to that file ({@link ProjectSettingsFile}, keeping the rest of it) instead of to the user's settings.
 * The other rows are greyed out. A row the project sets says so and offers "Use my setting", which removes the
 * project's value. The project's values apply only in a trusted folder; the bar says so and offers to trust it.
 *
 * <p>The window registers each scopable control under its key ({@code editor.tabSize}, {@code build.maven},
 * {@code lspEnabled.java}, …) with how to show a value in it; its listeners ask {@link #write} first and its
 * stored-value suppliers ask {@link #shown}, so the same controls serve both scopes.
 */
final class ProjectScopeSettings {

    /** One scopable control: its row, and how to show a value in the control. */
    private record Scoped(Node control, Consumer<Object> show) {}

    private final Supplier<ProjectScope> scope;
    private final Runnable applied;
    private final BooleanSupplier loading;
    private final Runnable reloadControls;
    private final Runnable trust;
    private final Supplier<javafx.stage.Window> owner;
    private final Map<String, Scoped> scoped = new LinkedHashMap<>();
    private final Map<Node, Object> lockedFrom = new java.util.IdentityHashMap<>();

    /** A row's user-scope marker and reset action, kept while Project mode lends the row its own. */
    private record Lent(boolean modified, Runnable reset) {}

    private final Map<Node, Lent> lent = new java.util.IdentityHashMap<>();
    private final ToggleGroup group = new ToggleGroup();
    private final ToggleButton userButton = new ToggleButton(tr("settings.scope.user"));
    private final ToggleButton projectButton = new ToggleButton(tr("settings.scope.project"));
    private final Label note = new Label();
    private final Button trustButton = new Button(tr("settings.scope.trust"));
    private final HBox bar;
    private Supplier<List<Node>> rows = List::of;

    /**
     * @param applied runs after a project value was written (the window's apply: every window re-reads its
     *     project file and re-lays out its buffers)
     * @param loading whether the window is showing stored values (its control listeners then write nothing)
     * @param reloadControls shows the stored values in every control again (the window's load)
     * @param trust trusts the project folder (asks first)
     * @param owner the Settings window, which owns the message when a project file cannot be written
     */
    ProjectScopeSettings(
            Supplier<ProjectScope> scope,
            Runnable applied,
            BooleanSupplier loading,
            Runnable reloadControls,
            Runnable trust,
            Supplier<javafx.stage.Window> owner) {
        this.scope = scope;
        this.applied = applied;
        this.loading = loading;
        this.reloadControls = reloadControls;
        this.trust = trust;
        this.owner = owner;
        userButton.setToggleGroup(group);
        projectButton.setToggleGroup(group);
        userButton.setSelected(true);
        userButton.getStyleClass().add(atlantafx.base.theme.Styles.LEFT_PILL);
        projectButton.getStyleClass().add(atlantafx.base.theme.Styles.RIGHT_PILL);
        // A toggle group lets the selected button be clicked off; the scope is always one of the two.
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                group.selectToggle(was);
            } else if (now != was) {
                reloadControls.run(); // shows the chosen scope's values, then refresh() marks the rows
            }
        });
        Label label = new Label(tr("settings.scope.label"));
        label.setLabelFor(userButton);
        note.getStyleClass().add("settings-hint");
        note.setWrapText(true);
        HBox.setHgrow(note, Priority.ALWAYS);
        trustButton.setOnAction(e -> trust.run());
        bar = new HBox(8, label, new HBox(userButton, projectButton), note, trustButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("settings-scope-bar");
        SettingsSearch.setShown(bar, false);
    }

    /** The bar for the top of the window; shown only in a project window. */
    Node bar() {
        return bar;
    }

    /** Where the window's rows come from, so Project mode can grey out the ones a project cannot set. */
    void setRows(Supplier<List<Node>> rows) {
        this.rows = rows;
    }

    /** Keys with no user setting: their rows can be changed only in Project mode. */
    private final java.util.Set<String> projectOnly = new java.util.HashSet<>();

    /**
     * The control of a save rule that exists only per project: Not set / On / Off, greyed out in User mode,
     * written to the project file in Project mode.
     */
    javafx.scene.control.ComboBox<String> projectOnlyCombo(String key) {
        javafx.scene.control.ComboBox<String> combo = new javafx.scene.control.ComboBox<>();
        combo.getItems().setAll("", "true", "false");
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String v) {
                return v == null || v.isEmpty()
                        ? tr("settings.scope.notSet")
                        : tr("true".equals(v) ? "common.on" : "common.off");
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        combo.setMinWidth(170);
        combo.valueProperty().addListener((obs, was, now) -> {
            if (!loading.getAsBoolean() && now != null) {
                write(key, now.isEmpty() ? null : Boolean.valueOf(now));
            }
        });
        register(key, combo, v -> combo.setValue(v == null ? "" : String.valueOf(v)));
        projectOnly.add(key);
        return combo;
    }

    /** Registers the control of setting {@code key}, and how to show a value in it. */
    void register(String key, Node control, Consumer<Object> show) {
        scoped.put(key, new Scoped(control, show));
    }

    /** Whether the window edits the project's settings. False in a window without a project. */
    boolean projectMode() {
        return projectButton.isSelected() && scope.get().root() != null;
    }

    /** {@code user}, or in Project mode the project's value of {@code key} when its file sets one. */
    @SuppressWarnings("unchecked")
    <T> T shown(String key, T user) {
        if (!projectMode()) {
            return user;
        }
        Object own = valueOf(scope.get().settings(), key);
        return own == null ? user : (T) own;
    }

    /**
     * In Project mode, writes {@code value} (null or blank = remove) as the project's {@code key} and returns
     * true — the caller then leaves the user's setting alone. In User mode returns false. While the window
     * shows stored values it writes nothing and returns true in Project mode (a control being shown a value
     * is not an edit).
     */
    boolean write(String key, Object value) {
        if (!projectMode()) {
            return false;
        }
        if (loading.getAsBoolean()) {
            return true;
        }
        Path root = scope.get().root();
        int dot = key.indexOf('.');
        String section = switch (key.substring(0, dot)) {
            case "build" -> "buildCommands";
            case "debug" -> "debugTools";
            default -> key.substring(0, dot);
        };
        Object stored = value instanceof String s && s.isBlank() ? null : value;
        try {
            ProjectSettingsFile.set(root, section, key.substring(dot + 1), stored);
        } catch (ProjectSettingsFile.NewerFileException e) {
            error(tr("settings.scope.newerFile", ProjectSettings.fileFor(root).toString()));
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            error(tr(
                    "settings.scope.writeFailed",
                    ProjectSettings.fileFor(root).toString(),
                    String.valueOf(e.getMessage())));
            return true;
        }
        scope.get().reload();
        applied.run();
        refresh();
        return true;
    }

    /**
     * Shows the project's values in the scopable controls (Project mode; called while the window is loading).
     * The project-only rules always show the project's choice, or Not set.
     */
    void showProjectValues() {
        boolean project = projectMode();
        ProjectSettings ps = scope.get().settings();
        scoped.forEach((key, s) -> {
            Object own = project || projectOnly.contains(key) ? valueOf(ps, key) : null;
            if (own != null || projectOnly.contains(key)) {
                s.show().accept(own);
            }
        });
    }

    /** Shows or hides the bar, greys out the rows a project cannot set, and writes each row's scope note. */
    void refresh() {
        ProjectScope sc = scope.get();
        Path root = sc.root();
        SettingsSearch.setShown(bar, root != null);
        boolean project = projectMode();
        ProjectSettings ps = sc.settings();
        boolean trusted = sc.trusted();
        boolean untrustedValues = !ps.isEmpty() && !trusted;
        note.setText(
                root == null
                        ? ""
                        : project
                                ? tr(
                                                "settings.scope.projectNote",
                                                ProjectSettings.fileFor(root).toString())
                                        + (untrustedValues ? " " + tr("settings.scope.untrusted") : "")
                                : untrustedValues ? tr("settings.scope.untrusted") : "");
        SettingsSearch.setShown(trustButton, root != null && untrustedValues);
        java.util.Set<Node> scopedRows = new java.util.HashSet<>();
        java.util.Set<Node> projectOnlyRows = new java.util.HashSet<>();
        scoped.forEach((key, s) -> {
            Node row = rowOf(s.control());
            if (row == null) {
                return;
            }
            scopedRows.add(row);
            if (projectOnly.contains(key)) {
                projectOnlyRows.add(row);
            }
            boolean own = valueOf(ps, key) != null;
            SettingRowState state = SettingRowState.of(row);
            if (project && !lent.containsKey(row)) {
                lent.put(row, new Lent(state.isModified(), state.resetAction())); // the user scope's, given back below
            } else if (!project && lent.containsKey(row)) {
                Lent was = lent.remove(row);
                state.setModified(was.modified());
                state.setResetAction(was.reset());
            }
            if (project) {
                state.setScopeNote(
                        own ? tr(trusted ? "settings.scope.setHere" : "settings.scope.setHereUntrusted") : null);
                state.setModified(own);
                state.setResetLabel(own ? tr("settings.scope.useMine") : null);
                state.setResetAction(own ? () -> write(key, null) : null);
            } else {
                // The language-server cards say what the project changes in a line of their own.
                boolean lsp = key.startsWith("lsp");
                state.setScopeNote(
                        own && !lsp
                                ? tr(trusted ? "settings.scope.overridden" : "settings.scope.overriddenUntrusted")
                                : null);
                state.setResetLabel(null);
            }
        });
        for (Node row : rows.get()) {
            lock(row, project ? !scopedRows.contains(row) : projectOnlyRows.contains(row));
        }
    }

    /** Says, after the change that caused it, that a project file could not be written. */
    private void error(String message) {
        javafx.application.Platform.runLater(() -> {
            javafx.scene.control.Alert failed = Dialogs.styled(new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR, message, javafx.scene.control.ButtonType.OK));
            failed.initOwner(owner.get());
            failed.setHeaderText(null);
            failed.showAndWait();
        });
    }

    private void lock(Node row, boolean on) {
        if (row.disableProperty().isBound()) {
            SettingsDependents.lockOf(row).set(on);
            return;
        }
        if (on && !lockedFrom.containsKey(row)) {
            lockedFrom.put(row, row.isDisable());
            row.setDisable(true);
        } else if (!on && lockedFrom.containsKey(row)) {
            row.setDisable((Boolean) lockedFrom.remove(row));
        }
    }

    /** The registered row (one of the window's rows) that holds {@code control}, or null. */
    private Node rowOf(Node control) {
        java.util.Set<Node> all = new java.util.HashSet<>(rows.get());
        for (Node n = control; n != null; n = n.getParent()) {
            if (all.contains(n)) {
                return n;
            }
        }
        // A switch row's checkbox is not in the scene; its SettingSwitch is, and knows its checkbox.
        for (Node row : all) {
            if (holdsSwitchFor(row, control)) {
                return row;
            }
        }
        return null;
    }

    private static boolean holdsSwitchFor(Node node, Node check) {
        if (node instanceof SettingSwitch sw && sw.getProperties().get(SettingSwitch.class) == check) {
            return true;
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                if (holdsSwitchFor(child, check)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The project file's value for {@code key}, or null when it sets none (or nothing usable). */
    static Object valueOf(ProjectSettings ps, String key) {
        int dot = key.indexOf('.');
        String section = key.substring(0, dot);
        String name = key.substring(dot + 1);
        ProjectSettings.Editor e = ps.editor();
        return switch (section) {
            case "editor" ->
                switch (name) {
                    case "indentStyle" -> e.getIndentStyle();
                    case "tabSize" -> e.getTabSize();
                    case "rulers" -> e.getRulers();
                    case "languageRulers" -> e.getLanguageRulers();
                    case "wordWrap" -> e.getWordWrap();
                    case "wordWrapProse" -> e.getWordWrapProse();
                    case "trimTrailingWhitespace" -> e.getTrimTrailingWhitespace();
                    case "insertFinalNewline" -> e.getInsertFinalNewline();
                    case "spellLanguage" -> e.getSpellLanguage();
                    default -> null;
                };
            case "build" -> blankToNull(ps.getBuildCommands().get(name));
            case "debug" -> blankToNull(ps.getDebugTools().get(name));
            case "lspCommands" -> blankToNull(ps.getLspCommands().get(name));
            case "lspEnabled" -> ps.getLspEnabled().get(name);
            default -> null;
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
