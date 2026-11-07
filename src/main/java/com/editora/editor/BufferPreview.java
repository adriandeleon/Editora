package com.editora.editor;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.diagram.DiagramKind;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.markdown.DiagramImages;
import com.editora.editor.markdown.MarkdownPreview;
import com.editora.editor.markdown.MarkdownRenderer;
import com.editora.editor.markdown.MermaidImages;
import com.editora.editor.markdown.PreviewButtons;
import com.editora.editor.markdown.PreviewFind;
import com.editora.editor.markdown.PreviewImageLoader;
import com.editora.editor.markdown.PreviewLayout;
import com.editora.editor.markdown.PreviewMenu;
import com.editora.editor.markdown.PreviewPrefs;
import com.editora.editor.markdown.PreviewSurfaces;
import com.editora.editor.markdown.SvgImages;
import com.editora.editor.markdown.TypstImages;
import com.editora.editor.structured.CrontabPreview;
import com.editora.editor.structured.DockerfilePreview;
import com.editora.editor.structured.FstabPreview;
import com.editora.editor.structured.GithubActionsPreview;
import com.editora.editor.structured.MarkwhenCalendar;
import com.editora.editor.structured.MarkwhenTimeline;
import com.editora.editor.structured.OpenApiDoc;
import com.editora.editor.structured.PomPreview;
import com.editora.editor.structured.SshConfigPreview;
import com.editora.editor.structured.StructuredTree;
import com.editora.editor.structured.SystemdPreview;
import com.editora.editor.structured.XmlTree;
import com.editora.structured.StructuredParser;
import com.editora.structured.XmlParser;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The rendered side of a buffer's Editor/Split/Preview modes: the preview pane and the hosts each kind of preview
 * sits in (Markdown and image previews in a scroll pane, the CSV grid, the HTTP responses and the self-scrolling
 * tree family), the off-thread rendering of every format into them, and the preview's zoom, theme, loading
 * overlay and keyboard paging. {@link EditorBuffer} keeps the public API and decides which preview a file has;
 * this class renders it. FX thread only, except the parsing submitted to {@link #PREVIEW_POOL}.
 */
final class BufferPreview {

    private final EditorBuffer b;

    BufferPreview(EditorBuffer b) {
        this.b = b;
    }

    /** The rendered Markdown document shown in {@link #previewPane} (lazy; see {@link MarkdownPreview}). */
    @Nullable
    MarkdownPreview markdownPreview;
    /** Rendered-preview pane (lazy); its content is rebuilt by {@link MarkdownRenderer}. */
    @Nullable
    ScrollPane previewPane;
    /** Wraps the preview so the floating control can overlay it in PREVIEW mode (no code pane then). */
    @Nullable
    StackPane previewHost;
    /** Centered spinner + message shown over the preview while content is expected but not yet rendered
     *  (e.g. an AI explanation streaming in) — otherwise the pane just looks blank. Toggled by
     *  {@link #setPreviewLoading}; always present in {@link #previewHost()}, hidden by default. */
    private @Nullable Node previewLoadingOverlay;

    private @Nullable Label previewLoadingLabel;
    /** The preview's −/+ zoom + light/dark control (overlaid top-left of the preview when previewing). */
    private @Nullable HBox zoomControl;
    /** The preview light/dark toggle button (a sun/moon glyph); reflects the current effective theme. */
    private @Nullable Button previewThemeButton;
    /** Markdown preview color theme: "" (follow app), "light", or "dark" — set by the controller. */
    private String previewThemeMode = "";
    /** Whether the app/editor theme is dark (used to resolve "follow app" + the toggle glyph). */
    private boolean previewAppDark;
    /** Runs the controller's global preview-theme toggle (injected, like the snippet/completion providers). */
    private Runnable previewThemeToggle = () -> {};
    /** Preview text zoom factor (1.0 = 100%); scales the rendered preview's base font size. */
    private double previewFontScale = 1.0;
    /** Where the Split view's divider sits (the editor's share), kept across mode switches and per file. */
    double previewDivider = 0.5;
    /** Told when the zoom or the divider changed by the user's hand (the controller persists them). */
    Runnable onPreviewLayoutChanged = () -> {};
    /** Repopulates the injected CSV grid from the buffer text; run on the debounced preview pulse. */
    private Runnable csvPreviewRefresh = () -> {};
    /** Wraps the CSV grid so the floating Editor/Split/Preview toggle can overlay it in PREVIEW mode. */
    @Nullable
    StackPane csvPreviewHost;
    /** Wraps the HTTP panel so the floating Editor/Split/Preview toggle can overlay it in PREVIEW mode. */
    @Nullable
    StackPane httpPreviewHost;
    /** Holds the self-scrolling structured preview node (tree or OpenAPI docs); the Split/Preview side. */
    private @Nullable StackPane structuredContentHolder;
    /** PREVIEW-mode wrapper for {@link #structuredContentHolder} so the mode toggle can overlay it. */
    private @Nullable StackPane structuredPreviewHost;
    /** Tri-state view for a structured doc: {@code null}=auto (API docs for a spec, else tree), else forced. */
    private @Nullable Boolean structuredShowApiDocs;
    /** Whether the last structured render detected an OpenAPI/Swagger spec (drives the view-toggle status). */
    private boolean lastStructuredOpenApi;
    /** Bumped per preview render request; background results discard if stale. */
    long previewGen;
    /**
     * Off-thread Markdown parsing + syntax tokenizing, shared across <b>all</b> open buffers so the
     * threads don't accumulate one pair per file (each pool thread is a ~1-2 MB daemon stack — a session
     * with dozens of tabs previously meant dozens of mostly-idle threads). Sharing is safe because every
     * background result is re-validated against the per-buffer {@link #previewGen}/{@link BufferHighlighter#highlightGen}
     * counters (bumped on the FX thread at submit) before it touches buffer state — overlapping work for
     * one buffer just means only the latest generation's result is applied. Different buffers already
     * tokenize concurrently against the shared {@link GrammarRegistry} grammar, so a shared pool adds no
     * new grammar-concurrency. Daemon, app-lifetime (mirrors {@link PreviewImageLoader}/{@code MermaidImages}).
     */
    private static final ExecutorService PREVIEW_POOL =
            Executors.newFixedThreadPool(2, EditorBuffer.daemonFactory("markdown-preview"));

    /** Runs the {@code pom.toggleView} command (summary ⇄ XML tree) from the preview's right-click menu. */
    private Runnable pomViewToggleHandler = () -> {};
    /** The menu item for that switch — relabelled per show, and hidden for a non-pom tree preview. */
    private @Nullable MenuItem pomViewItem;

    private javafx.scene.control.@Nullable ContextMenu treePreviewContextMenu;
    /** Whether the tree preview on screen is an error message (as last rendered: nothing is parsed here). */
    boolean previewShowsError() {
        return b.markdownViewMode != EditorBuffer.MarkdownViewMode.EDITOR
                && structuredContentHolder != null
                && structuredContentHolder.lookup(".structured-error") != null;
    }

    /** Whether the last render of this structured buffer detected an OpenAPI/Swagger spec. */
    boolean isStructuredOpenApi() {
        return lastStructuredOpenApi;
    }

    /** Flips a structured doc between the tree and the OpenAPI-docs view, then re-renders the preview. */
    void toggleStructuredView() {
        boolean currentDocs = structuredShowApiDocs == null || structuredShowApiDocs;
        structuredShowApiDocs = !currentDocs;
        if (b.markdownViewMode != EditorBuffer.MarkdownViewMode.EDITOR) {
            scheduleRenderPreview();
        }
    }

    // --- Live Mermaid linting (maid) ----------------------------------------------------------------

    /** Injects the CSV grid preview node (a ui-layer {@code CsvGridPanel}); {@code null} removes it. Non-null
     *  makes the buffer previewable ({@link EditorBuffer#hasPreview()}), so the Editor/Split/Preview toggle attaches. */
    void setCsvPreviewNode(@Nullable Node node) {
        if (b.csvPreviewNode == node) {
            return;
        }
        b.csvPreviewNode = node;
        if (node == null) {
            csvPreviewHost = null;
            if (b.markdownViewMode != EditorBuffer.MarkdownViewMode.EDITOR) {
                b.markdownViewMode = EditorBuffer.MarkdownViewMode.EDITOR; // the grid is gone — fall back to source
            }
        }
        b.viewLayout.rebuildViewHost();
    }

    /** Injects the callback that repopulates the CSV grid from the buffer text (run on the debounced pulse). */
    void setCsvPreviewRefresh(Runnable refresh) {
        this.csvPreviewRefresh = refresh == null ? () -> {} : refresh;
    }

    /** Injects the HTTP response panel (a ui-layer {@code HttpClientPanel}); {@code null} removes it. Non-null
     *  makes the buffer previewable ({@link EditorBuffer#hasPreview()}), so the Editor/Split/Preview toggle attaches. */
    void setHttpPreviewNode(@Nullable Node node) {
        if (b.httpPreviewNode == node) {
            return;
        }
        b.httpPreviewNode = node;
        if (node == null) {
            httpPreviewHost = null;
            if (b.markdownViewMode != EditorBuffer.MarkdownViewMode.EDITOR) {
                b.markdownViewMode = EditorBuffer.MarkdownViewMode.EDITOR; // the panel is gone — fall back to source
            }
        }
        b.viewLayout.rebuildViewHost();
    }

    /** Switches the Markdown view mode and rebuilds the view host. */
    void setMarkdownViewMode(EditorBuffer.MarkdownViewMode mode) {
        EditorBuffer.MarkdownViewMode target = mode == null ? EditorBuffer.MarkdownViewMode.EDITOR : mode;
        boolean changed = b.markdownViewMode != target;
        EditorBuffer.MarkdownViewMode from = b.markdownViewMode;
        if (changed) {
            b.scrollSync.leaving(from); // the view that comes on screen takes over this one's place
        }
        b.markdownViewMode = target;
        b.selectionBars.scheduleFormatBar(); // hide the format bar when entering pure PREVIEW, re-evaluate otherwise
        b.selectionBars.scheduleAiActionsBar(); // same, for the AI selection-actions bar
        if (target != EditorBuffer.MarkdownViewMode.EDITOR) {
            b.split = EditorBuffer.Split.NONE; // preview supersedes any code split
            scheduleRenderPreview();
        } else if (markdownPreview != null) {
            markdownPreview.clear(); // nothing shows it: give its nodes back
        }
        b.viewLayout.rebuildViewHost();
        b.setMinimapVisible(b.minimapVisible); // re-apply: the minimap is hidden while the preview is shown
        // The paging-focus + scroll-sync tail is Markdown-preview-specific (the CSV grid, the HTTP response
        // panel and the structured tree/docs scroll themselves and have no ScrollPane host), so skip those.
        if (!b.hasCsvPreview() && !b.hasHttpPreview() && !b.hasTreePreview()) {
            if (target == EditorBuffer.MarkdownViewMode.PREVIEW) {
                // Focus the preview so the paging keys (Space/PageDown/Backspace/PageUp) work without a click.
                Platform.runLater(previewPane()::requestFocus);
            }
            b.scrollSync.entered(from, target); // same source line in the view that is now showing
        }
        if (changed) {
            b.onViewModeChanged.run();
        }
    }

    ScrollPane previewPane() {
        if (previewPane == null) {
            previewPane = new ScrollPane();
            previewPane.setFitToWidth(true);
            previewPane.getStyleClass().add("markdown-preview-pane");
            // Apply the independent preview theme picked before the pane existed.
            if ("light".equals(previewThemeMode)) {
                previewPane.getStyleClass().add("md-light");
            } else if ("dark".equals(previewThemeMode)) {
                previewPane.getStyleClass().add("md-dark");
            }
            // Keyboard scrolling in the preview: Space / PageDown page down, Backspace / PageUp page up
            // (C-v / M-v go through nav.pageDown/Up → pagePreview). Focus it on click so the keys land.
            previewPane.setFocusTraversable(true);
            previewPane.addEventFilter(
                    javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                    e -> previewPane().requestFocus());
            // Right-click menu: Copy (rendered text), the link or code block under the pointer, exports, Print.
            previewPane.setOnContextMenuRequested(e -> {
                Node hit = e.getPickResult() == null ? null : e.getPickResult().getIntersectedNode();
                b.previewMenu.show(previewPane(), hit, e.getSceneY(), e.getScreenX(), e.getScreenY());
                e.consume();
            });
            previewPane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShortcutDown()) {
                    return; // leave modifier combos to the global keymap (e.g. C-v / M-v / zoom)
                }
                if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.PAGE_DOWN) {
                    scrollPreviewPage(0.9);
                    e.consume();
                } else if (e.getCode() == KeyCode.BACK_SPACE || e.getCode() == KeyCode.PAGE_UP) {
                    scrollPreviewPage(-0.9);
                    e.consume();
                }
            });
            b.scrollSync.install(previewPane); // SPLIT-mode scroll sync, preview → editor half
        }
        return previewPane;
    }

    /** Injects the pom preview's "Show as XML" / "Show POM Summary" action (the controller command, so the
     *  menu route and the palette route report the same status and share the refusal guard). */
    void setPomViewToggleHandler(Runnable handler) {
        this.pomViewToggleHandler = handler == null ? () -> {} : handler;
    }

    /** Copy / Select All aimed at the rendered preview when that is where the user is
     *  ({@link PreviewMenu#editCommand}); false: the editor's own command applies. */
    boolean previewEditCommand(boolean selectAll) {
        return b.previewMenu.editCommand(previewPane, selectAll);
    }

    /** Scrolls the preview by {@code pages} viewport heights (negative: up; infinite: to that end); no-op if
     *  there's nothing to scroll. A "page" of 0.9 leaves a little overlap. */
    private void scrollPreviewPage(double pages) {
        if (previewPane == null || previewPane.getContent() == null) {
            return;
        }
        double viewport = previewPane.getViewportBounds().getHeight();
        double contentH = previewPane.getContent().getLayoutBounds().getHeight();
        double scrollable = contentH - viewport;
        if (scrollable <= 0) {
            return; // content fits — nothing to page
        }
        double range = previewPane.getVmax() - previewPane.getVmin();
        double next = Double.isInfinite(pages)
                ? (pages > 0 ? previewPane.getVmax() : previewPane.getVmin())
                : previewPane.getVvalue() + (viewport * pages) / scrollable * range;
        previewPane.setVvalue(Math.max(previewPane.getVmin(), Math.min(previewPane.getVmax(), next)));
    }

    /** As {@link EditorBuffer#pagePreview}, by {@code pages} viewport heights: a small fraction for a line, an infinite
     *  one for the start or end of the document. */
    boolean scrollPreview(double pages) {
        // (Split only: a pane taken off the scene on the way back to Editor can still report focus.)
        boolean previewActive = b.markdownViewMode == EditorBuffer.MarkdownViewMode.PREVIEW
                || (b.markdownViewMode == EditorBuffer.MarkdownViewMode.SPLIT
                        && previewPane != null
                        && previewPane.isFocusWithin());
        if (!previewActive || previewPane == null) {
            return false;
        }
        scrollPreviewPage(pages);
        return true;
    }

    /**
     * Forces a preview re-render (e.g. after the Mermaid feature/theme toggles so {@code ```mermaid}
     * blocks switch between diagram and code). No-op in Editor mode.
     */
    void refreshPreview() {
        scheduleRenderPreview();
    }

    /** The search over the rendered Markdown preview (the Find bar uses it while {@link EditorBuffer#isPreviewOnly()}),
     *  or null when this buffer has rendered none. */
    @Nullable
    PreviewFind previewFind() {
        return markdownPreview == null ? null : markdownPreview.find();
    }

    boolean scrollPreviewToAnchor(String slug) {
        return b.markdownViewMode != EditorBuffer.MarkdownViewMode.EDITOR
                && markdownPreview != null
                && markdownPreview.scrollToAnchor(slug);
    }

    void scheduleRenderPreview() {
        if (b.markdownViewMode == EditorBuffer.MarkdownViewMode.EDITOR) {
            return;
        }
        if (b.hasCsvPreview()) {
            csvPreviewRefresh.run(); // the coordinator re-parses the buffer text into its per-buffer grid
            return;
        }
        if (b.hasHttpPreview()) {
            // The HTTP panel shows the result of *running* a request, not a rendering of the buffer text —
            // re-rendering on the debounced edit pulse would be meaningless (and firing requests would be
            // catastrophic). It is repopulated only by HttpClientCoordinator when a run completes. This
            // branch exists so the buffer never falls through to the Markdown tail below.
            return;
        }
        if (b.hasGithubActionsPreview()) {
            // A workflow is also YAML, so this must precede the structured (YAML tree) branch to win. Parse
            // off-thread, build the specialized workflow digest on the FX thread into the shared host.
            String src = b.documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    com.editora.ghactions.Workflow parsed = com.editora.ghactions.Workflow.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(GithubActionsPreview.build(parsed));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (b.hasStructuredPreview()) {
            // Whole file is one JSON/YAML/TOML doc: parse off-thread (pure, cheap), build the tree / OpenAPI
            // docs on the FX thread into the self-scrolling structured host. The previewGen guard drops a
            // superseded render (mirrors the Markwhen branch).
            StructuredParser.Format sfmt = b.structuredFormat();
            String src = b.documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    StructuredParser.Parsed parsed = StructuredParser.parse(src, sfmt);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            renderStructured(parsed);
                        }
                    });
                } catch (Throwable t) {
                    // submit() would swallow a Throwable (e.g. a NoClassDefFoundError while loading a Jackson
                    // format factory on this pool thread) into its Future — a silent blank preview. Surface it.
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (b.hasPomPreview()) {
            // Whole file is a Maven pom: parse off-thread into the display summary (coordinates, properties,
            // dependencies, plugins, profiles), build the section list on the FX thread into the shared
            // self-scrolling host. Must precede the XML branch — a pom is XML, and its summary wins unless
            // the user switched this buffer to the generic tree.
            String src = b.documentTextSnapshot();
            Path pomPath = b.path; // a local pom's parent chain and BOMs are read beside it (Maven review C6)
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    com.editora.maven.PomSummary parsed = com.editora.maven.PomSummary.parse(src, pomPath);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder()
                                    .getChildren()
                                    .setAll(PomPreview.build(parsed, line -> b.jumpToLine(line - 1)));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (b.hasXmlPreview()) {
            // Whole file is one XML doc: parse off-thread (JDK DOM), build the DOM tree on the FX thread into
            // the same self-scrolling host as the JSON/YAML/TOML tree. The previewGen guard drops a stale render.
            String src = b.documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    XmlParser.Parsed parsed = XmlParser.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            renderXml(parsed);
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (b.hasCrontabPreview()) {
            // Whole file is a crontab: parse off-thread (pure, cheap), decode each schedule + compute the next
            // fire times, build the preview node on the FX thread into the shared self-scrolling host. The
            // "now" is captured here on the FX thread so the render is deterministic (mirrors the tree branch).
            String src = b.documentTextSnapshot();
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    com.editora.cron.Crontab parsed = com.editora.cron.Crontab.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(CrontabPreview.build(parsed, now));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (b.hasFstabPreview()) {
            // Whole file is an /etc/fstab: parse off-thread (pure, cheap), decode each mount line into English,
            // build the preview node on the FX thread into the shared self-scrolling host. previewGen-guarded.
            String src = b.documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    java.util.List<com.editora.fstab.FstabEntry> parsed = com.editora.fstab.Fstab.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(FstabPreview.build(parsed));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (b.hasSystemdPreview()) {
            // Whole file is a systemd unit: parse off-thread, decode directives (+ OnCalendar next runs) into
            // English, build the preview node on the FX thread into the shared self-scrolling host.
            String src = b.documentTextSnapshot();
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    com.editora.systemd.SystemdUnit parsed = com.editora.systemd.SystemdUnit.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(SystemdPreview.build(parsed, now));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (b.hasSshConfigPreview()) {
            // Whole file is an SSH client config: parse off-thread into Host/Match blocks, decode into English.
            String src = b.documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    java.util.List<com.editora.sshconfig.SshConfig.Block> parsed =
                            com.editora.sshconfig.SshConfig.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(SshConfigPreview.build(parsed));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (b.hasDockerfilePreview()) {
            // Whole file is a Dockerfile: parse off-thread into build stages, distill each into a digest.
            String src = b.documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    com.editora.dockerfile.Dockerfile parsed = com.editora.dockerfile.Dockerfile.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(DockerfilePreview.build(parsed));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (b.isDiagram()) {
            // Whole file is one diagram: build the (async-filling) node on the FX thread directly. The
            // preview zoom scales the fit width (not font size, which doesn't affect an ImageView); the
            // content-hash cache makes a zoom re-render a cheap re-fit of the same image. Don't call
            // applyPreviewScale() here — it re-enters this branch for diagrams (would recurse).
            double v = previewPane().getVvalue();
            double scale = previewFontScale;
            // A stable per-buffer surface key so live-editing pulses coalesce (only the latest render spawns
            // mmdc) instead of piling up ~4 s Chromium renders on every 250 ms pause (#458).
            String surfaceKey = PreviewSurfaces.mermaid(b.path, b);
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(
                    MermaidImages.node(b.documentTextSnapshot(), lw -> lw * scale, surfaceKey));
            box.getStyleClass().add("markdown-preview");
            StackPane wrap = new StackPane(box);
            wrap.getStyleClass().add("markdown-preview-wrap");
            previewPane().setContent(wrap);
            Platform.runLater(() -> previewPane().setVvalue(v));
            return;
        }
        if (b.hasSvgPreview()) {
            // Whole file is one SVG: rasterize off-thread via JSVG (in-process, no CLI), same async-image
            // model as Mermaid/diagrams. Zoom scales the fit width; the content-hash cache makes a zoom
            // re-render a cheap re-fit.
            double v = previewPane().getVvalue();
            double scale = previewFontScale;
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(
                    SvgImages.node(b.documentTextSnapshot(), lw -> lw * scale, this::jumpToSource));
            box.getStyleClass().add("markdown-preview");
            StackPane wrap = new StackPane(box);
            wrap.getStyleClass().add("markdown-preview-wrap");
            previewPane().setContent(wrap);
            Platform.runLater(() -> previewPane().setVvalue(v));
            return;
        }
        DiagramKind dk = b.diagramKind();
        if (dk != null) {
            // Whole file is one DOT/PlantUML diagram — same async-image model as Mermaid (see above), via
            // the generic DiagramImages façade. Zoom scales the fit width; the content-hash cache makes a
            // zoom re-render a cheap re-fit. Don't call applyPreviewScale() here (it re-enters this branch).
            double v = previewPane().getVvalue();
            double scale = previewFontScale;
            String surfaceKey = "diagram@" + System.identityHashCode(b);
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(
                    DiagramImages.node(dk, b.documentTextSnapshot(), lw -> lw * scale, surfaceKey));
            box.getStyleClass().add("markdown-preview");
            StackPane wrap = new StackPane(box);
            wrap.getStyleClass().add("markdown-preview-wrap");
            previewPane().setContent(wrap);
            Platform.runLater(() -> previewPane().setVvalue(v));
            return;
        }
        if (b.hasTypstPreview()) {
            // Whole file is one Typst document rendered to stacked page images via the typst CLI — the same
            // async-image model as Mermaid/diagrams, but multi-page. Zoom scales the fit width; the
            // content-hash cache + retain-last-image (in TypstImages) make live editing update in place
            // without flicker.
            double v = previewPane().getVvalue();
            double scale = previewFontScale;
            // The throwaway input is written in the file's own folder so relative #image/#import resolve as
            // on disk; --root is that folder or a higher project root (via the injected resolver) so a
            // multi-file project's up-references resolve too. Local saved files only — a remote (SFTP) path
            // can't be a working dir for the local typst process, so both fall back to an isolated temp root.
            java.nio.file.Path localPath =
                    b.path != null && b.path.getFileSystem() == java.nio.file.FileSystems.getDefault() ? b.path : null;
            java.nio.file.Path fileDir = localPath != null ? localPath.getParent() : null;
            java.nio.file.Path root =
                    localPath != null && b.typstRootResolver != null ? b.typstRootResolver.apply(localPath) : fileDir;
            String retainKey = PreviewSurfaces.typstRetain(b.path, b);
            String surfaceKey = PreviewSurfaces.typst(b);
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(TypstImages.node(
                    b.documentTextSnapshot(),
                    lw -> lw * scale,
                    retainKey,
                    surfaceKey,
                    fileDir,
                    root,
                    b.getDisplayName()));
            box.getStyleClass().add("markdown-preview");
            StackPane wrap = new StackPane(box);
            wrap.getStyleClass().add("markdown-preview-wrap");
            previewPane().setContent(wrap);
            Platform.runLater(() -> previewPane().setVvalue(v));
            return;
        }
        if (b.isMarkwhen()) {
            // Whole file is one timeline. Parse off-thread (pure, cheap), build the node on the FX thread.
            // Horizontal zoom scales the axis width; preserve both scroll positions (the axis scrolls
            // horizontally). The previewGen guard drops a superseded render.
            String src = b.documentTextSnapshot();
            double vw = previewPane().getViewportBounds().getWidth();
            double scale = previewFontScale;
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                com.editora.markwhen.Timeline model = com.editora.markwhen.MarkwhenParser.parse(src);
                Platform.runLater(() -> {
                    if (gen != previewGen) {
                        return;
                    }
                    double v = previewPane().getVvalue();
                    double h = previewPane().getHvalue();
                    Node timeline = b.markwhenView == EditorBuffer.MarkwhenView.CALENDAR
                            ? MarkwhenCalendar.build(model, scale, vw)
                            : MarkwhenTimeline.build(model, scale, vw);
                    javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(timeline);
                    box.getStyleClass().add("markdown-preview");
                    StackPane wrap = new StackPane(box);
                    wrap.getStyleClass().add("markdown-preview-wrap");
                    previewPane().setContent(wrap);
                    previewPane().setVvalue(v);
                    previewPane().setHvalue(h);
                });
            });
            return;
        }
        String md = b.documentTextSnapshot();
        Path baseDir = b.path == null ? null : b.path.getParent();
        long gen = ++previewGen;
        PREVIEW_POOL.submit(() -> {
            // Guarded like every other preview branch. commonmark 0.30.0 added input limits that *throw*
            // rather than degrade — a table over a million cells is the reachable one (the nesting limits
            // fall back to plain text instead). Unguarded, submit() parks that in a Future nobody reads, so
            // the preview would sit blank with nothing logged and no way to tell it apart from a slow render.
            try {
                MarkdownPreview.Plan plan = MarkdownPreview.plan(md, baseDir); // parse + per-block keys
                Platform.runLater(() -> {
                    if (gen != previewGen) {
                        return; // a newer render superseded this one
                    }
                    if (markdownPreview == null) {
                        markdownPreview = new MarkdownPreview();
                        markdownPreview.attach(previewPane());
                        markdownPreview.onRerender(this::scheduleRenderPreview);
                        markdownPreview.onTaskToggle(l -> b.isEditable() && MarkdownPreview.toggleTask(b.area, l));
                        markdownPreview.onJumpToSource(l -> b.scrollSync.jumpToSource(
                                l, () -> setMarkdownViewMode(EditorBuffer.MarkdownViewMode.SPLIT)));
                    }
                    // Only the blocks that changed are rebuilt; the reader's source line stays at the top.
                    Boolean dark = previewThemeMode.isEmpty() ? null : Boolean.valueOf("dark".equals(previewThemeMode));
                    markdownPreview.setDiagrams(!b.encryption.isEncrypted()); // mmdc reads its source from a file
                    markdownPreview.show(plan, b.openUrlHandler, dark, b.scrollSync.anchorLine());
                    applyPreviewScale(); // keep the current zoom on the freshly rendered content
                });
            } catch (Throwable t) {
                surfaceMarkdownPreviewError(gen, t);
            }
        });
    }

    /**
     * The PREVIEW-mode wrapper for whichever preview this buffer has. Each self-scrolling preview (CSV grid,
     * HTTP responses, the structured/tree family) brings its own {@code StackPane} so the floating mode toggle
     * can be overlaid on it; everything else falls through to the Markdown-style {@link #previewHost()}.
     */
    StackPane previewModeHost() {
        if (b.hasCsvPreview()) {
            return csvPreviewHost();
        }
        if (b.hasHttpPreview()) {
            return httpPreviewHost();
        }
        if (b.hasTreePreview()) {
            return structuredPreviewHost();
        }
        return previewHost(); // preview (+ zoom for markdown)
    }

    StackPane previewHost() {
        if (previewHost == null) {
            previewHost = new StackPane();
        }
        // preview content + the −/+ zoom control (top-left, clear of the mode toggle at top-right) + the
        // (normally hidden) centered loading overlay.
        previewHost.getChildren().setAll(previewPane(), zoomControl(), previewLoadingOverlay());
        StackPane.setAlignment(zoomControl(), Pos.TOP_LEFT);
        StackPane.setMargin(zoomControl(), new Insets(6, 0, 0, 6));
        StackPane.setAlignment(previewLoadingOverlay(), Pos.CENTER);
        if (b.isMarkdown() && !b.previewLive()) {
            // Too large to follow every edit: say so, and offer the render the pulse no longer does.
            previewHost.getChildren().add(PreviewButtons.pausedStrip(this::refreshPreview));
        }
        return previewHost;
    }

    /** Wraps the injected CSV grid in a StackPane so the floating Editor/Split/Preview toggle can overlay it
     *  in PREVIEW mode (the grid has no scroll-pane host like Markdown; it manages its own scrolling). */
    StackPane csvPreviewHost() {
        if (csvPreviewHost == null) {
            csvPreviewHost = new StackPane();
        }
        csvPreviewHost.getChildren().setAll(b.csvPreviewNode); // the toggle is added by rebuildViewHost()
        belowModeToggle(b.csvPreviewNode);
        return csvPreviewHost;
    }

    /** Wraps the injected HTTP response panel so the mode toggle can overlay it in PREVIEW mode (the panel
     *  scrolls its own response body, so it needs no ScrollPane host — mirrors {@link #csvPreviewHost()}). */
    StackPane httpPreviewHost() {
        if (httpPreviewHost == null) {
            httpPreviewHost = new StackPane();
        }
        httpPreviewHost.getChildren().setAll(b.httpPreviewNode); // the toggle is added by rebuildViewHost()
        belowModeToggle(b.httpPreviewNode);
        return httpPreviewHost;
    }

    /**
     * Height of the band the floating Editor/Split/Preview toggle takes at the top of a preview — the same
     * 34px the Markdown preview leaves above its document ({@code .markdown-preview-pane} in app.css).
     */
    private static final double MODE_TOGGLE_BAND = 34;

    /**
     * Starts a self-scrolling preview (CSV grid, HTTP responses, structured tree) below the mode toggle.
     * Each opens with a toolbar or a first row reaching the right edge, and the toggle — on top — hid its
     * rightmost buttons and swallowed their clicks.
     */
    private static void belowModeToggle(@Nullable Node content) {
        StackPane.setMargin(content, new Insets(MODE_TOGGLE_BAND, 0, 0, 0));
    }

    /** Sets the freshly parsed structured node (tree / OpenAPI docs / error) into the self-scrolling holder. */
    private void renderStructured(StructuredParser.Parsed parsed) {
        lastStructuredOpenApi = parsed.isOpenApi();
        Node node;
        if (!parsed.ok() && parsed.line() > 0) { // JSON Lines: where the bad line is, as a link
            node = com.editora.editor.structured.ParseErrorView.of(
                    String.valueOf(parsed.error()),
                    parsed.line(),
                    parsed.column(),
                    this::jumpToSource,
                    "structured-error");
        } else if (!parsed.ok()) {
            Label err = new Label(parsed.error());
            err.getStyleClass().add("structured-error");
            err.setWrapText(true);
            node = err;
        } else {
            boolean showDocs = parsed.isOpenApi() && showsApiDocs();
            node = showDocs ? OpenApiDoc.build(parsed.openApi()) : StructuredTree.build(parsed.root());
        }
        structuredContentHolder().getChildren().setAll(node);
    }

    /** Sets the freshly parsed XML DOM tree (or an error label) into the shared self-scrolling holder. */
    private void renderXml(XmlParser.Parsed parsed) {
        Node node = parsed.ok()
                ? XmlTree.build(parsed.root())
                : com.editora.editor.structured.ParseErrorView.of(
                        String.valueOf(parsed.error()),
                        parsed.line(),
                        parsed.column(),
                        this::jumpToSource,
                        "structured-error");
        structuredContentHolder().getChildren().setAll(node);
    }

    /** A preview's parse-error link: the caret to that 1-based line and column (XML review C15). */
    private void jumpToSource(int line, int column) {
        com.editora.editor.structured.ParseErrorView.jump(b, line, column);
    }

    /** The {@link #surfaceTreePreviewError} counterpart for the Markdown preview, which has its own pane. */
    private void surfaceMarkdownPreviewError(long gen, Throwable t) {
        java.util.logging.Logger.getLogger(EditorBuffer.class.getName())
                .log(java.util.logging.Level.WARNING, "markdown preview render failed", t);
        String msg = t.getMessage() != null ? t.getMessage() : t.toString();
        Platform.runLater(() -> {
            if (gen != previewGen) {
                return;
            }
            Label err = new Label(msg);
            err.getStyleClass().add("structured-error");
            err.setWrapText(true);
            previewPane().setContent(err);
        });
    }

    /**
     * Surfaces a Throwable from a {@code PREVIEW_POOL.submit(...)} tree-preview task (structured/XML) that the
     * task's {@code Future} would otherwise swallow into a silent blank — the {@code exec.submit}
     * Error-swallowing trap the project conventions warn about — as a logged warning + a visible error label
     * in the shared holder, so a failed render is diagnosable rather than a blank pane.
     */
    private void surfaceTreePreviewError(long gen, Throwable t) {
        java.util.logging.Logger.getLogger(EditorBuffer.class.getName())
                .log(java.util.logging.Level.WARNING, "tree preview render failed", t);
        String msg = t.getMessage() != null ? t.getMessage() : t.toString();
        Platform.runLater(() -> {
            if (gen != previewGen) {
                return;
            }
            Label err = new Label(msg);
            err.getStyleClass().add("structured-error");
            err.setWrapText(true);
            structuredContentHolder().getChildren().setAll(err);
        });
    }

    /** The self-scrolling structured preview node holder — used directly as the Split preview side. */
    private StackPane structuredContentHolder() {
        if (structuredContentHolder == null) {
            structuredContentHolder = new StackPane();
            // The JSON/YAML/TOML + XML trees host their own TreeView (not previewPane), so they need their
            // own right-click menu — an Export to PDF (a full snapshot of the tree), like every other preview.
            structuredContentHolder.setOnContextMenuRequested(
                    e -> showTreePreviewContextMenu(e.getScreenX(), e.getScreenY()));
        }
        return structuredContentHolder;
    }

    private void showTreePreviewContextMenu(double screenX, double screenY) {
        if (treePreviewContextMenu == null) {
            MenuItem pdf = new MenuItem(tr("command.preview.exportPdf"));
            pdf.setGraphic(MenuIcons.download());
            pdf.setOnAction(ev -> b.previewMenu.exportPdf());
            MenuItem print = new MenuItem(tr("command.preview.print"));
            print.setGraphic(MenuIcons.print());
            print.setOnAction(ev -> b.previewMenu.print());
            pomViewItem = new MenuItem();
            pomViewItem.setGraphic(MenuIcons.code());
            pomViewItem.setOnAction(ev -> pomViewToggleHandler.run());
            treePreviewContextMenu = new javafx.scene.control.ContextMenu(pomViewItem, pdf, print);
            treePreviewContextMenu.getStyleClass().add("editor-context-menu");
        }
        // The pom summary is the only tree-hosted preview with a second rendering of the same file, so its
        // switch is the only item here that isn't shown for every one of them. Relabelled per show (it names
        // the view it switches *to*), since the buffer can be flipped from the palette between two shows.
        boolean pom = b.isPom();
        MenuItem pomItem = pomViewItem; // built with the menu above
        if (pomItem != null) {
            pomItem.setVisible(pom);
            if (pom) {
                pomItem.setText(tr(b.pomShowXml ? "pom.menu.showSummary" : "pom.menu.showXml"));
            }
        }
        treePreviewContextMenu.show(structuredContentHolder(), screenX, screenY);
    }

    // ---- Preview → PDF snapshot (Markwhen / JSON-YAML-TOML / XML / summaries) — see PreviewSnapshots ----

    /** Whether an OpenAPI document shows its docs view (the default) rather than the tree behind it. */
    boolean showsApiDocs() {
        return structuredShowApiDocs == null || structuredShowApiDocs;
    }

    /** Wraps the structured holder so the floating Editor/Split/Preview toggle can overlay it in PREVIEW mode. */
    private StackPane structuredPreviewHost() {
        if (structuredPreviewHost == null) {
            structuredPreviewHost = new StackPane();
        }
        structuredPreviewHost
                .getChildren()
                .setAll(structuredContentHolder()); // the toggle is added by rebuildViewHost()
        belowModeToggle(structuredContentHolder());
        return structuredPreviewHost;
    }

    /**
     * Centered spinner + message overlaid on the preview while it's expected to render soon but hasn't
     * yet — e.g. an AI-generated explanation streams into the buffer, but the debounced preview re-render
     * only fires ~250ms after the text stops changing, so a fast continuous stream can leave the preview
     * blank for its whole duration with no feedback otherwise. Toggled by {@link #setPreviewLoading}.
     */
    private Node previewLoadingOverlay() {
        if (previewLoadingOverlay == null) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setMaxSize(32, 32);
            previewLoadingLabel = new Label();
            previewLoadingLabel.getStyleClass().add("markdown-preview-loading-label");
            VBox box = new VBox(10, spinner, previewLoadingLabel);
            box.setAlignment(Pos.CENTER);
            box.getStyleClass().add("markdown-preview-loading");
            box.setVisible(false);
            box.setManaged(false);
            box.setMouseTransparent(true); // never intercepts clicks meant for the preview underneath
            previewLoadingOverlay = box;
        }
        return previewLoadingOverlay;
    }

    /**
     * Shows or hides the preview's centered loading spinner (see {@link #previewLoadingOverlay()}).
     * {@code message} replaces the label text when non-null; pass {@code false} once real content is
     * ready (or generation fails) to hide it again.
     */
    void setPreviewLoading(boolean loading, @Nullable String message) {
        Node overlay = previewLoadingOverlay();
        Label label = previewLoadingLabel; // built with the overlay
        if (message != null && label != null) {
            label.setText(message);
        }
        overlay.setVisible(loading);
        overlay.setManaged(loading);
    }

    private HBox zoomControl() {
        if (zoomControl == null) {
            Button out = PreviewButtons.named("−", tr("project.map.preview.zoomOut"), this::zoomPreviewOut);
            Button in = PreviewButtons.named("+", tr("project.map.preview.zoomIn"), this::zoomPreviewIn);
            // Light/dark preview-theme toggle (independent of the app theme). Glyph shows what a click
            // switches TO: a moon while the preview is light, a sun while it's dark.
            previewThemeButton = PreviewButtons.named("", "", () -> previewThemeToggle.run());
            updateThemeButtonGlyph();
            zoomControl = new HBox(out, in, previewThemeButton);
            zoomControl.getStyleClass().add("md-zoom");
            zoomControl.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            zoomControl.setPickOnBounds(false);
        }
        return zoomControl;
    }

    /** Injects the controller's global preview light/dark toggle (run by the floating sun/moon button). */
    void setPreviewThemeToggle(Runnable toggle) {
        if (toggle != null) {
            this.previewThemeToggle = toggle;
        }
    }

    /**
     * Applies the Markdown preview color theme, independent of the app theme. {@code mode} is "" (follow
     * the app theme), "light", or "dark"; {@code appDark} is the app theme's brightness (for "follow" +
     * the toggle glyph). Adds {@code md-light}/{@code md-dark} to the preview pane (which redefine the
     * looked-up colors for the preview subtree) and refreshes the toggle glyph.
     */
    void applyPreviewTheme(String mode, boolean appDark) {
        boolean changed = !previewThemeMode.equals(mode == null ? "" : mode);
        this.previewThemeMode = mode == null ? "" : mode;
        this.previewAppDark = appDark;
        if (changed && b.isMarkdown()) {
            scheduleRenderPreview(); // formulas and diagrams are bitmaps: draw them for the new theme
        }
        if (previewPane != null) {
            previewPane.getStyleClass().removeAll("md-light", "md-dark");
            if ("light".equals(previewThemeMode)) {
                previewPane.getStyleClass().add("md-light");
            } else if ("dark".equals(previewThemeMode)) {
                previewPane.getStyleClass().add("md-dark");
            }
        }
        updateThemeButtonGlyph();
    }

    private void updateThemeButtonGlyph() {
        if (previewThemeButton == null) {
            return;
        }
        // The glyph and name say what a click switches TO: ☀ light, ☾ dark, ◐ back to following the app.
        String next = PreviewButtons.nextTheme(previewThemeMode, previewAppDark);
        previewThemeButton.setText(next.isEmpty() ? "◐" : next.equals("light") ? "☀" : "☾");
        PreviewButtons.name(previewThemeButton, tr(PreviewButtons.themeActionKey(next)));
    }

    /** Zooms the preview text in/out (multiplicative steps, clamped) or resets to 100%. */
    void zoomPreviewIn() {
        setPreviewFontScale(previewFontScale * 1.1);
    }

    void zoomPreviewOut() {
        setPreviewFontScale(previewFontScale / 1.1);
    }

    void resetPreviewZoom() {
        setPreviewFontScale(1.0);
    }

    private void setPreviewFontScale(double scale) {
        previewFontScale = Math.max(PreviewLayout.MIN_ZOOM, Math.min(PreviewLayout.MAX_ZOOM, scale));
        applyPreviewScale();
        onPreviewLayoutChanged.run();
    }

    /** The preview's zoom and Split divider as they are now. */
    PreviewLayout getPreviewLayout() {
        return new PreviewLayout(previewFontScale, previewDivider);
    }

    /** Restores a remembered zoom and divider (no change notification); {@code onChange} hears later ones. */
    void restorePreviewLayout(@Nullable PreviewLayout layout, Runnable onChange) {
        this.onPreviewLayoutChanged = onChange == null ? () -> {} : onChange;
        if (layout != null) {
            previewFontScale = layout.zoom();
            previewDivider = layout.divider();
            applyPreviewScale();
        }
    }

    /** Applies the current zoom: re-fits a diagram to the scaled width, else overrides the preview's base
     *  font size (headings use em). */
    private void applyPreviewScale() {
        if (previewPane == null || previewPane.getContent() == null) {
            return;
        }
        if (b.isDiagram() || b.isRenderedDiagram() || b.hasSvgPreview() || b.hasTypstPreview()) {
            scheduleRenderPreview(); // re-fit the diagram/SVG/Typst image to the new zoom (cache hit — cheap)
            return;
        }
        if (b.isMarkwhen()) {
            scheduleRenderPreview(); // re-render the timeline at the new axis scale (parse is cheap + off-thread)
            return;
        }
        previewPane.getContent().setStyle(PreviewPrefs.fontStyle(previewFontScale));
        if (markdownPreview != null) {
            markdownPreview.setZoom(previewFontScale); // formulas, images and diagrams are not text: sized too
        }
    }
}
