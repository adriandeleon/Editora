package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.text.LanguageRegistry;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the debuggee has loaded, as palette-style lists. <em>Debug: Loaded Sources…</em> (VS Code's Loaded Scripts)
 * lists the scripts and source files the adapter reports — the program's own files first, then the rest — and opens
 * the chosen one: a file in a tab, where breakpoints are set as in any other, and a source the adapter has only by
 * reference (Node's internals, code run through {@code eval}) read-only, with the text the adapter gives — the same
 * tab a stop in that code shows ({@code DebugCoordinator.openReferenceSource}).
 * <em>Debug: Modules…</em> (Visual Studio's Modules window) lists the shared libraries, assemblies or Python modules
 * loaded, with whether their symbols are; Enter copies a module's path. js-debug lists sources; CodeLLDB, netcoredbg
 * and debugpy list modules.
 */
final class LoadedSourcesPicker {

    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;
    private final Supplier<DapManager> dap;
    /** The live session's debugger, as the status line names it ("JavaScript (js-debug)"). */
    private final Supplier<String> adapterLabel;

    /** Opens a source the debugger has only by reference, read-only (once per session). */
    private final java.util.function.Consumer<DapModels.LoadedSource> openReference;

    LoadedSourcesPicker(
            CoordinatorHost host,
            DebugCoordinator.Ops ops,
            Supplier<DapManager> dap,
            Supplier<String> adapterLabel,
            java.util.function.Consumer<DapModels.LoadedSource> openReference) {
        this.host = host;
        this.ops = ops;
        this.dap = dap;
        this.adapterLabel = adapterLabel;
        this.openReference = openReference;
    }

    // --- loaded sources ---------------------------------------------------------------------------------------------

    /** Debug: Loaded Sources… — asks the session, then lists what it says. */
    void showSources() {
        DapManager d = dap.get();
        if (!d.isActive()) {
            host.setStatus(tr("palette.disabled.needsDebugSession"));
            return;
        }
        if (!d.listsLoadedSources()) {
            host.setStatus(tr("status.debug.noLoadedSources", adapterLabel.get()));
            return;
        }
        d.loadedSources(list -> {
            if (list.isEmpty()) {
                host.setStatus(tr("status.debug.nothingLoaded"));
                return;
            }
            showSources(sorted(list, ops.projectRoot()));
        });
    }

    /** The list on screen (a test reads it). */
    @Nullable
    QuickOpen<DapModels.LoadedSource> sourcesPicker;

    private void showSources(List<DapModels.LoadedSource> sources) {
        Path root = ops.projectRoot();
        Path home = homeDir();
        QuickOpen<DapModels.LoadedSource> picker = new QuickOpen<>(
                tr("command.debug.loadedSources"),
                tr("debug.loadedSources.prompt", sources.size()),
                () -> sources,
                DapModels.LoadedSource::name,
                s -> sourceDetail(s, root, home),
                s -> s.name() + " " + s.adapterPath(),
                s -> {
                    if (s != null) {
                        open(s);
                    }
                });
        picker.setPreferredSize(BreakpointsPicker.WIDTH, 1);
        picker.setOverlayHost(host.overlayHost());
        sourcesPicker = picker;
        picker.show(host.window());
    }

    /**
     * {@code sources} in the order the list shows them: files of the project, then other files, then sources the
     * adapter has only by reference; each by name.
     */
    static List<DapModels.LoadedSource> sorted(List<DapModels.LoadedSource> sources, @Nullable Path root) {
        List<DapModels.LoadedSource> out = new ArrayList<>(sources);
        out.sort(Comparator.comparingInt((DapModels.LoadedSource s) -> rank(s, root))
                .thenComparing(DapModels.LoadedSource::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DapModels.LoadedSource::adapterPath));
        return out;
    }

    private static int rank(DapModels.LoadedSource s, @Nullable Path root) {
        if (s.file() == null) {
            return 2;
        }
        return root != null && s.file().startsWith(root) ? 0 : 1;
    }

    /** A source's second line: a file's folder (project-relative, or with {@code ~}); else what the adapter calls it. */
    static String sourceDetail(DapModels.LoadedSource s, @Nullable Path root, @Nullable Path home) {
        List<String> parts = new ArrayList<>();
        if (s.file() != null) {
            String folder = BreakpointsPicker.folder(s.file().getParent(), root, home);
            parts.add(folder.isEmpty() ? tr("debug.loadedSources.projectRoot") : folder);
        } else {
            if (!s.adapterPath().isEmpty() && !s.adapterPath().equals(s.name())) {
                parts.add(s.adapterPath());
            }
            parts.add(tr("debug.loadedSources.readOnly"));
        }
        if (s.origin() != null && !s.origin().isBlank()) {
            parts.add(s.origin());
        }
        return String.join("  ·  ", parts);
    }

    /** Opens {@code s}: a file in a tab; anything else read-only, with the text the adapter gives for it. */
    void open(DapModels.LoadedSource s) {
        Path file = s.file();
        if (file != null && (file.getFileSystem() != java.nio.file.FileSystems.getDefault() || Files.exists(file))) {
            ops.openPath(file);
            return;
        }
        if (s.sourceReference() <= 0) {
            host.setStatus(tr("status.debug.sourceUnavailable", s.name()));
            return;
        }
        openReference.accept(s);
    }

    /** The language to highlight a source the adapter gave as: by its name; Node's internals are JavaScript. */
    static String languageOf(DapModels.LoadedSource s) {
        String name = s.adapterPath().isEmpty() ? s.name() : s.adapterPath();
        if (name.startsWith("<node_internals>") || name.startsWith("node:")) {
            return "javascript";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return LanguageRegistry.forFileName(name.substring(slash + 1));
    }

    // --- modules ----------------------------------------------------------------------------------------------------

    /** Debug: Modules… — asks the session, then lists what it says. */
    void showModules() {
        DapManager d = dap.get();
        if (!d.isActive()) {
            host.setStatus(tr("palette.disabled.needsDebugSession"));
            return;
        }
        if (!d.listsModules()) {
            host.setStatus(tr("status.debug.noModules", adapterLabel.get()));
            return;
        }
        d.modules(list -> {
            if (list.isEmpty()) {
                host.setStatus(tr("status.debug.nothingLoaded"));
                return;
            }
            showModules(sortedModules(list));
        });
    }

    /** The list on screen (a test reads it). */
    @Nullable
    QuickOpen<DapModels.ModuleInfo> modulesPicker;

    private void showModules(List<DapModels.ModuleInfo> modules) {
        QuickOpen<DapModels.ModuleInfo> picker = new QuickOpen<>(
                tr("command.debug.modules"),
                tr("debug.modules.prompt", modules.size()),
                () -> modules,
                DapModels.ModuleInfo::name,
                LoadedSourcesPicker::moduleDetail,
                m -> m.name() + " " + (m.path() == null ? "" : m.path()),
                m -> {
                    if (m != null) {
                        copyPath(m);
                    }
                });
        picker.addRowAction(
                new KeyCodeCombination(KeyCode.C, KeyCombination.ALT_DOWN),
                tr("debug.modules.keyCopy", "Alt+C"),
                this::copyPath);
        picker.setPreferredSize(BreakpointsPicker.WIDTH, 1);
        picker.setOverlayHost(host.overlayHost());
        modulesPicker = picker;
        picker.show(host.window());
    }

    /** {@code modules}: the user's own first (where the adapter says), then by name. */
    static List<DapModels.ModuleInfo> sortedModules(List<DapModels.ModuleInfo> modules) {
        List<DapModels.ModuleInfo> out = new ArrayList<>(modules);
        out.sort(Comparator.comparingInt((DapModels.ModuleInfo m) -> Boolean.TRUE.equals(m.userCode()) ? 0 : 1)
                .thenComparing(DapModels.ModuleInfo::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** A module's second line: its symbols, version, user code / optimized, address range and path. */
    static String moduleDetail(DapModels.ModuleInfo m) {
        List<String> parts = new ArrayList<>();
        if (m.symbolStatus() != null && !m.symbolStatus().isBlank()) {
            parts.add(m.symbolStatus().strip());
        }
        if (m.version() != null && !m.version().isBlank()) {
            parts.add(tr("debug.modules.version", m.version().strip()));
        }
        if (Boolean.TRUE.equals(m.userCode())) {
            parts.add(tr("debug.modules.userCode"));
        }
        if (Boolean.TRUE.equals(m.optimized())) {
            parts.add(tr("debug.modules.optimized"));
        }
        if (m.addressRange() != null && !m.addressRange().isBlank()) {
            parts.add(m.addressRange().strip());
        }
        if (m.path() != null && !m.path().isBlank() && !m.path().equals(m.name())) {
            parts.add(m.path());
        }
        return String.join("  ·  ", parts);
    }

    /** Copies {@code m}'s path (its name when it has none) and says so. */
    void copyPath(DapModels.ModuleInfo m) {
        String text = m.path() == null || m.path().isBlank() ? m.name() : m.path();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        host.setStatus(tr("status.debug.modulePathCopied", text));
    }

    private static @Nullable Path homeDir() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : Path.of(home);
    }
}
