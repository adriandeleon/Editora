package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;

import com.editora.config.Breakpoint;
import com.editora.config.BreakpointExport;
import com.editora.config.MemberBreakpoint;
import com.editora.editor.EditorBuffer;
import com.editora.editor.debug.BreakpointManager;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Breakpoint groups and breakpoint files. A breakpoint — on a line, a method or a field — can be in a named group
 * (IntelliJ's breakpoint groups): <em>Debug: Move Breakpoint to Group…</em> puts the caret line's in one, and
 * <em>Debug: Enable / Disable Breakpoint Group…</em> turns a whole group on or off. <em>Debug: Export
 * Breakpoints…</em> writes every breakpoint, or one group's, to a file, and <em>Debug: Import Breakpoints…</em> sets
 * a file's breakpoints again (Visual Studio's Export / Import), skipping places that already have one.
 */
final class BreakpointGroups {

    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;
    private final Supplier<BreakpointsPicker> picker;
    private final @Nullable MemberBreakpointCommands members;
    /** Sends breakpoints just saved for files with no tab (by file key) to each live session. */
    private final Consumer<Map<String, List<Breakpoint>>> sendClosed;

    /** Where an export is written: the save dialog, or what a test answers. */
    Function<FileChooser, java.io.@Nullable File> chooseSave;

    /** The file an import reads: the open dialog, or what a test answers. */
    Function<FileChooser, java.io.@Nullable File> chooseOpen;

    BreakpointGroups(
            CoordinatorHost host,
            DebugCoordinator.Ops ops,
            Supplier<BreakpointsPicker> picker,
            @Nullable MemberBreakpointCommands members,
            Consumer<Map<String, List<Breakpoint>>> sendClosed) {
        this.host = host;
        this.ops = ops;
        this.picker = picker;
        this.members = members;
        this.sendClosed = sendClosed;
        this.chooseSave = chooser -> chooser.showSaveDialog(host.window());
        this.chooseOpen = chooser -> chooser.showOpenDialog(host.window());
    }

    // --- groups -------------------------------------------------------------------------------------------------

    /** An editable list of the groups there are, holding {@code current}: the breakpoint form's Group field. */
    ComboBox<String> groupField(String current) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setEditable(true);
        combo.getItems().setAll(BreakpointsPicker.groups(picker.get().entries()));
        combo.getEditor().setText(current);
        combo.setValue(current.isEmpty() ? null : current);
        combo.getEditor().setPromptText(tr("dialog.debug.groupPrompt"));
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
    }

    /** The group a {@link #groupField} names now (what is typed, else what is chosen). */
    static String groupIn(ComboBox<String> combo) {
        String typed = combo.getEditor().getText();
        return Breakpoint.groupName(typed != null ? typed : combo.getValue());
    }

    /**
     * Debug: Move Breakpoint to Group… — asks for the group of the caret line's breakpoint of {@code b} (one there is,
     * or a new name; blank for none).
     */
    void moveToGroupAtCaret(EditorBuffer b) {
        int line = b.getFocusedArea().getCurrentParagraph();
        BreakpointManager mgr = b.getBreakpointManager();
        Breakpoint bp = mgr.get(line);
        if (bp == null) {
            host.setStatus(tr("status.debug.noBreakpointHere"));
            return;
        }
        ComboBox<String> group = groupField(bp.group());
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        Label lineText = new Label(
                BreakpointFormText.lineText(b.getArea().getParagraph(line).getText(), 64));
        lineText.getStyleClass().add("debug-breakpoint-line");
        grid.add(lineText, 0, 0, 2, 1);
        grid.add(new Label(tr("dialog.debug.groupLabel")), 0, 1);
        grid.add(group, 1, 1);
        GridPane.setHgrow(group, Priority.ALWAYS);
        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.debug.groupTitle", line + 1),
                grid,
                group,
                tr("dialog.ok"),
                null,
                () -> {
                    String name = groupIn(group);
                    mgr.setGroup(line, name);
                    b.refreshGutterLine(line);
                    host.setStatus(
                            name.isEmpty()
                                    ? tr("status.debug.groupNone", line + 1)
                                    : tr("status.debug.groupSet", line + 1, name));
                },
                null,
                false);
    }

    /**
     * Debug: Enable / Disable Breakpoint Group… — the groups, each marked when all its breakpoints are on; choosing
     * one turns them all on, or all off when they all are.
     */
    void toggleGroupPicker() {
        BreakpointsPicker list = picker.get();
        List<BreakpointsPicker.Entry> all = list.entries();
        List<String> groups = BreakpointsPicker.groups(all);
        if (groups.isEmpty()) {
            host.setStatus(tr("status.debug.noGroups"));
            return;
        }
        QuickOpen<String> choose = new QuickOpen<>(
                tr("command.debug.toggleBreakpointGroup"),
                tr("debug.groups.prompt"),
                () -> groups,
                g -> BreakpointsPicker.groupRow(g, list.entries()),
                g -> "",
                g -> {
                    if (g != null) {
                        list.toggleGroup(g);
                    }
                });
        choose.setOverlayHost(host.overlayHost());
        choose.show(host.window());
    }

    // --- export -------------------------------------------------------------------------------------------------

    /** Debug: Export Breakpoints… — every breakpoint, or (asked when there are groups) one group's, to a file. */
    void exportBreakpoints() {
        List<BreakpointsPicker.Entry> all = picker.get().entries();
        if (all.isEmpty()) {
            host.setStatus(tr("status.debug.noBreakpoints"));
            return;
        }
        List<String> groups = BreakpointsPicker.groups(all);
        if (groups.isEmpty()) {
            exportTo(all, "");
            return;
        }
        List<String> scopes = new ArrayList<>();
        scopes.add(""); // every breakpoint
        scopes.addAll(groups);
        QuickOpen<String> choose = new QuickOpen<>(
                tr("command.debug.exportBreakpoints"),
                tr("debug.export.prompt"),
                () -> scopes,
                g -> g.isEmpty()
                        ? tr("debug.export.all", all.size())
                        : tr(
                                "debug.breakpoints.groupRow",
                                g,
                                BreakpointsPicker.inGroup(all, g).size()),
                g -> "",
                g -> {
                    if (g != null) {
                        exportTo(g.isEmpty() ? all : BreakpointsPicker.inGroup(all, g), g);
                    }
                });
        choose.setOverlayHost(host.overlayHost());
        choose.show(host.window());
    }

    /** Asks where, then writes {@code entries} there; {@code group} names the file ("" for all). */
    void exportTo(List<BreakpointsPicker.Entry> entries, String group) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("command.debug.exportBreakpoints"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(tr("debug.export.filter"), "*.json"));
        chooser.setInitialFileName(fileName(group));
        Path root = ops.projectRoot();
        if (root != null && Files.isDirectory(root)) {
            chooser.setInitialDirectory(root.toFile());
        }
        java.io.File chosen = chooseSave.apply(chooser);
        if (chosen == null) {
            return;
        }
        String text = BreakpointExport.write(exported(entries, root), exportedMembers(entries));
        try {
            Files.writeString(chosen.toPath(), text);
            host.setStatus(tr("status.debug.exported", entries.size(), chosen.getName()));
        } catch (IOException | RuntimeException e) {
            host.setStatus(tr("status.debug.exportFailed", chosen.getName(), String.valueOf(e.getMessage())));
        }
    }

    /** {@code breakpoints.json}, or a group's name made a file name. */
    static String fileName(String group) {
        String base = group.isEmpty()
                ? ""
                : group.replaceAll("[^\\p{L}\\p{N}._-]+", "-").replaceAll("^-+|-+$", "");
        return (base.isEmpty() ? "breakpoints" : "breakpoints-" + base) + ".json";
    }

    /** The line breakpoints of {@code entries} as written: files relative to {@code root} when under it. */
    static List<BreakpointExport.FileBreakpoint> exported(List<BreakpointsPicker.Entry> entries, @Nullable Path root) {
        List<BreakpointExport.FileBreakpoint> out = new ArrayList<>();
        for (BreakpointsPicker.Entry e : entries) {
            if (e.member() != null) {
                continue;
            }
            Breakpoint bp = e.breakpoint().withLine(e.line()); // whole-file line, even while a tab is narrowed
            if (e.buffer() != null && e.buffer().encryption().isEncrypted()) {
                bp = bp.withLineText(""); // an encrypted file's text never leaves it in plain
            }
            out.add(new BreakpointExport.FileBreakpoint(fileText(e.file(), e.key(), root), bp));
        }
        return out;
    }

    private static List<MemberBreakpoint> exportedMembers(List<BreakpointsPicker.Entry> entries) {
        List<MemberBreakpoint> out = new ArrayList<>();
        for (BreakpointsPicker.Entry e : entries) {
            if (e.member() != null) {
                out.add(e.member());
            }
        }
        return out;
    }

    /** How a breakpoint's file is written: {@code src/Main.java} under the project, else its stored form. */
    static String fileText(Path file, String key, @Nullable Path root) {
        if (root != null && file.getFileSystem() == root.getFileSystem() && file.startsWith(root)) {
            return root.relativize(file).toString().replace('\\', '/');
        }
        return key.isEmpty() ? file.toString() : key;
    }

    /** A written file name read back: relative to {@code root}, or a stored path; null when it names nothing. */
    static @Nullable Path fileOf(String text, @Nullable Path root) {
        String s = text.strip();
        if (s.isEmpty()) {
            return null;
        }
        boolean stored = s.startsWith("/")
                || s.startsWith("\\")
                || s.contains(":/")
                || (s.length() > 2 && s.charAt(1) == ':' && (s.charAt(2) == '\\' || s.charAt(2) == '/'));
        if (stored) {
            try {
                return Vfs.parseStorable(s);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return root == null ? null : root.resolve(s).normalize();
    }

    // --- import -------------------------------------------------------------------------------------------------

    /** What an import did: breakpoints set, ones whose place had one already, ones whose file is not there. */
    record Imported(int added, int existing, int missing) {}

    /** Debug: Import Breakpoints… — asks for a file Export wrote, then sets its breakpoints. */
    void importBreakpoints() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("command.debug.importBreakpoints"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(tr("debug.export.filter"), "*.json"));
        Path root = ops.projectRoot();
        if (root != null && Files.isDirectory(root)) {
            chooser.setInitialDirectory(root.toFile());
        }
        java.io.File chosen = chooseOpen.apply(chooser);
        if (chosen == null) {
            return;
        }
        BreakpointExport.Document doc;
        try {
            doc = BreakpointExport.read(Files.readString(chosen.toPath()));
        } catch (IOException | RuntimeException e) {
            host.setStatus(
                    tr("status.debug.breakpointsImportFailed", chosen.getName(), String.valueOf(e.getMessage())));
            return;
        }
        Imported done = apply(doc, root);
        host.setStatus(tr("status.debug.imported", done.added(), done.existing(), done.missing()));
    }

    /** Sets {@code doc}'s breakpoints, files resolved against {@code root}. */
    Imported apply(BreakpointExport.Document doc, @Nullable Path root) {
        Map<String, String> taken = new HashMap<>();
        for (BreakpointsPicker.Entry e : picker.get().entries()) {
            if (e.member() == null && !e.breakpoint().id().isEmpty()) {
                taken.put(e.breakpoint().id(), e.key() + ":" + e.line());
            }
        }
        List<BreakpointExport.FileBreakpoint> wanted = BreakpointExport.freshIds(doc.breakpoints(), taken, fb -> {
            Path p = fileOf(fb.file(), root);
            return p == null
                    ? null
                    : DebugCoordinator.key(p) + ":" + fb.breakpoint().line();
        });
        Map<Path, List<Breakpoint>> byFile = new LinkedHashMap<>();
        int missing = 0;
        for (BreakpointExport.FileBreakpoint fb : wanted) {
            Path p = fileOf(fb.file(), root);
            if (p == null || (p.getFileSystem() == java.nio.file.FileSystems.getDefault() && !Files.isRegularFile(p))) {
                missing++;
                continue;
            }
            byFile.computeIfAbsent(p, k -> new ArrayList<>()).add(fb.breakpoint());
        }
        int added = 0;
        int existing = 0;
        Map<String, List<Breakpoint>> closed = new LinkedHashMap<>();
        for (Map.Entry<Path, List<Breakpoint>> f : byFile.entrySet()) {
            EditorBuffer b = ops.bufferForPath(f.getKey());
            if (b != null && !b.isNarrowed()) {
                int[] counts = addToTab(b, f.getValue());
                added += counts[0];
                existing += counts[1];
            } else {
                String key = DebugCoordinator.key(f.getKey());
                List<Breakpoint> list = new ArrayList<>(ops.breakpointMap().getOrDefault(key, List.of()));
                for (Breakpoint bp : f.getValue()) {
                    if (list.stream().anyMatch(have -> have.line() == bp.line())) {
                        existing++;
                    } else {
                        list.add(bp);
                        added++;
                    }
                }
                list.sort(java.util.Comparator.comparingInt(Breakpoint::line));
                ops.breakpointMap().put(key, list);
                closed.put(key, list);
            }
        }
        if (!closed.isEmpty()) {
            ops.saveBreakpoints();
            closed.keySet().forEach(key -> ops.breakpointsStored(ops.breakpointMap(), key));
            sendClosed.accept(closed);
        }
        if (members != null && !doc.memberBreakpoints().isEmpty()) {
            int set = members.addAll(doc.memberBreakpoints());
            added += set;
            existing += doc.memberBreakpoints().size() - set;
        }
        return new Imported(added, existing, missing);
    }

    /**
     * Adds {@code bps} to {@code b}'s breakpoints, each on the line its text is on now (it may have moved since the
     * export): {added, already there}.
     */
    private static int[] addToTab(EditorBuffer b, List<Breakpoint> bps) {
        var area = b.getArea();
        BreakpointManager mgr = b.getBreakpointManager();
        var anchored = BreakpointManager.reanchor(
                bps,
                area.getParagraphs().size(),
                i -> area.getParagraph(i).getText(),
                BreakpointManager.MAX_REANCHOR_SCAN);
        int added = 0;
        int existing = bps.size() - anchored.size(); // two on one line: the second is not set
        for (Breakpoint bp : anchored.values()) {
            if (mgr.isBreakpoint(bp.line())) {
                existing++;
            } else {
                mgr.add(bp);
                b.refreshGutterLine(bp.line());
                added++;
            }
        }
        return new int[] {added, existing};
    }
}
