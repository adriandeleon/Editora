package com.editora.editor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.editora.config.Settings;
import org.jspecify.annotations.Nullable;

/**
 * The UI zoom: one setting ({@link Settings#getUiZoom}) scales the whole interface, the menus, toolbar, tabs
 * and status bar included. It is not the text zoom, which changes the editor's font alone.
 *
 * <p>Two halves, because an interface gets its sizes from two places:
 *
 * <ul>
 *   <li><b>Stylesheets.</b> The sheets are written at 100%. At any other zoom {@link #sheet} answers a copy with
 *       every length multiplied ({@link UiZoomCss}), the root font size among them, so every font follows one
 *       value. A scene or a node takes its sheets through {@link #sheet}; a change of zoom re-points the sheets
 *       of every open window, and a window shown later is re-pointed as it shows.
 *   <li><b>Code.</b> A size the code fixes (a panel's width, a row's padding, a glyph's scale) is set through
 *       {@link #follow} or one of the helpers built on it ({@link #padding}, {@link #prefWidth}, …), which set
 *       it now and again whenever the zoom changes. A plain {@code setPrefWidth(280)} stays 280 at every zoom.
 * </ul>
 *
 * <p>Content with a zoom of its own (the editor's text, a terminal, the Markdown preview, a Project Map) keeps
 * the size its own setting gives it.
 */
public final class UiZoom {

    private static final Logger LOG = Logger.getLogger(UiZoom.class.getName());

    /** One step of Zoom In or Zoom Out, in percent. */
    public static final int STEP = 10;

    /** The key, in a node's, scene's or window's properties, of the sizes that follow the zoom. */
    private static final String FOLLOWERS_KEY = "editora.uiZoom.followers";

    /** The key, in a scene's properties, that marks one kept at 100% ({@link #pin}). */
    private static final String PINNED_KEY = "editora.uiZoom.pinned";

    /** The key that marks a window whose scene is watched. */
    private static final String HOOKED_KEY = "editora.uiZoom.hooked";

    private static final ReadOnlyDoubleWrapper FACTOR = new ReadOnlyDoubleWrapper(1.0);

    /** The zoom, readable off the FX thread too (a node may be built on a worker before it joins a scene). */
    private static volatile int percent = Settings.DEFAULT_UI_ZOOM;

    private static volatile double factor = 1.0;

    /** Zoomed sheets by {@code base|percent}: a {@code data:} URL each, built once. */
    private static final Map<String, String> ZOOMED = new ConcurrentHashMap<>();

    /** The 100% sheet each zoomed URL was made from. */
    private static final Map<String, String> BASES = new ConcurrentHashMap<>();

    /**
     * What has a size that follows the zoom. Weak, and it holds the owners alone: an owner's followers live in
     * its own properties, since a follower usually captures its owner and a map value that reaches its own weak
     * key is never collected.
     */
    private static final Set<Object> OWNERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** The scenes kept at 100%, to give the theme's 100% sheet again when the theme or the zoom changes. */
    private static final Set<Scene> PINNED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static boolean installed;

    private UiZoom() {}

    // ---- the zoom ----

    /** The zoom in percent, 100 by default. */
    public static int percent() {
        return percent;
    }

    /** The zoom as a factor, 1 at 100%. */
    public static double factor() {
        return factor;
    }

    /** {@link #factor()} to observe. FX thread. */
    public static ReadOnlyDoubleProperty factorProperty() {
        return FACTOR.getReadOnlyProperty();
    }

    /** One step larger, up to the largest. Pure. */
    public static int in(int percent) {
        return Settings.clampUiZoom(percent + STEP);
    }

    /** One step smaller, down to the smallest. Pure. */
    public static int out(int percent) {
        return Settings.clampUiZoom(percent - STEP);
    }

    /**
     * Sets the zoom before any window exists: start-up, from the saved setting. Nothing is restyled, since
     * everything built afterwards reads the new value.
     */
    public static void init(int newPercent) {
        percent = Settings.clampUiZoom(newPercent);
        factor = percent / 100.0;
        FACTOR.set(factor);
    }

    /**
     * Changes the zoom and restyles every open window at once: their sheets are re-pointed and every size that
     * follows the zoom is set again. The caller sets the theme's user-agent sheet again afterwards (through
     * {@link #sheet}), since the theme is the window layer's. FX thread.
     *
     * @return whether the zoom changed
     */
    public static boolean apply(int newPercent) {
        int clamped = Settings.clampUiZoom(newPercent);
        if (clamped == percent) {
            return false;
        }
        init(clamped);
        for (Window window : List.copyOf(Window.getWindows())) {
            repoint(window);
        }
        refollow();
        return true;
    }

    /**
     * Keeps {@code scene} at 100% whatever the zoom: paper. A scene that lays a page out for print or export,
     * and the window that shows those pages, must measure and draw at the sizes the page was paginated at.
     * Its sheets are the 100% ones and, while the app is zoomed, its user-agent sheet is the theme's 100% one.
     */
    public static <S extends Scene> S pin(S scene) {
        scene.getProperties().put(PINNED_KEY, Boolean.TRUE);
        PINNED.add(scene);
        repin(scene);
        return scene;
    }

    /** Gives every pinned scene the theme's 100% sheet again: call after the theme's sheet changes. FX thread. */
    public static void themeChanged() {
        List<Scene> pinned;
        synchronized (PINNED) {
            pinned = new ArrayList<>(PINNED);
        }
        for (Scene scene : pinned) {
            repin(scene);
        }
    }

    private static void repin(Scene scene) {
        String theme = javafx.application.Application.getUserAgentStylesheet();
        String base = theme == null ? null : base(theme);
        // At 100% the app's own sheet serves, as it always did; a scene-level one is needed only to undo a zoom.
        scene.setUserAgentStylesheet(base == null || base.equals(theme) ? null : base);
        List<String> wanted = bases(scene.getStylesheets());
        if (!wanted.equals(scene.getStylesheets())) {
            scene.getStylesheets().setAll(wanted);
        }
    }

    /** {@code sheets} with each zoomed copy replaced by its 100% sheet, in order, without repeats. */
    public static List<String> bases(List<String> sheets) {
        List<String> out = new ArrayList<>(sheets.size());
        for (String sheet : sheets) {
            String base = sheet == null ? null : base(sheet);
            if (base != null && !out.contains(base)) {
                out.add(base);
            }
        }
        return out;
    }

    // ---- stylesheets ----

    /**
     * Watches the window list: a window is re-pointed as it shows and when its scene changes, so a popup built
     * before a zoom change, or a sheet added by code that never heard of the zoom, is right by the time it is
     * seen. Once, at start-up, on the FX thread.
     */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    hook(window);
                }
            }
        });
        for (Window window : List.copyOf(Window.getWindows())) {
            hook(window);
        }
    }

    private static void hook(Window window) {
        repoint(window);
        if (window.getProperties().putIfAbsent(HOOKED_KEY, Boolean.TRUE) == null) {
            window.sceneProperty().addListener((obs, old, scene) -> repoint(window));
        }
    }

    /**
     * {@code url} at the current zoom: itself at 100%, else a copy with every length scaled. Give it the 100%
     * sheet or a zoomed one; a sheet that cannot be read, and one that is not a {@code .css} file (a
     * {@code data:} sheet built in code), comes back as it is.
     */
    public static String sheet(String url) {
        return sheet(url, percent);
    }

    /** {@link #sheet(String)} at {@code atPercent}. */
    static String sheet(String url, int atPercent) {
        String base = base(url);
        if (atPercent == Settings.DEFAULT_UI_ZOOM || !isScalable(base)) {
            return base;
        }
        return ZOOMED.computeIfAbsent(base + "|" + atPercent, key -> {
            String zoomed = build(base, atPercent);
            if (zoomed == null) {
                return base;
            }
            BASES.put(zoomed, base);
            return zoomed;
        });
    }

    /** The 100% sheet behind {@code url}: itself, unless it is a copy {@link #sheet} made. */
    public static String base(String url) {
        return BASES.getOrDefault(url, url);
    }

    /** Whether {@code url} names a stylesheet file, as against a sheet some code built as a {@code data:} URL. */
    static boolean isScalable(String url) {
        return !url.startsWith("data:") && url.endsWith(".css");
    }

    private static @Nullable String build(String base, int atPercent) {
        try {
            URL url = base.contains(":") ? URI.create(base).toURL() : UiZoom.class.getResource(base);
            if (url == null) {
                return null;
            }
            String css;
            try (InputStream in = url.openStream()) {
                css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String scaled = UiZoomCss.scaleSheet(css, atPercent / 100.0);
            if (scaled.equals(css)) {
                return null; // no lengths in it (the UI font sheet, a syntax palette): the sheet itself serves
            }
            return "data:text/css;base64,"
                    + Base64.getEncoder().encodeToString(scaled.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Could not scale the stylesheet " + base + " for the UI zoom", e);
            return null;
        }
    }

    /** {@code sheets} with each stylesheet file at the current zoom, in order, without repeats. Pure but for the cache. */
    static List<String> repointed(List<String> sheets) {
        List<String> out = new ArrayList<>(sheets.size());
        for (String sheet : sheets) {
            String wanted = sheet == null ? null : sheet(sheet);
            if (wanted != null && !out.contains(wanted)) {
                out.add(wanted);
            }
        }
        return out;
    }

    private static void repoint(ObservableList<String> sheets) {
        if (sheets.isEmpty()) {
            return;
        }
        List<String> wanted = repointed(sheets);
        if (!wanted.equals(sheets)) {
            sheets.setAll(wanted);
        }
    }

    private static void repoint(Window window) {
        Scene scene = window.getScene();
        if (scene == null || (percent == Settings.DEFAULT_UI_ZOOM && BASES.isEmpty())) {
            return; // nothing was ever zoomed, so nothing can be stale
        }
        if (scene.getProperties().containsKey(PINNED_KEY)) {
            repin(scene);
            return;
        }
        repoint(scene.getStylesheets());
        if (scene.getRoot() != null) {
            repoint(scene.getRoot());
        }
    }

    /** Re-points the sheets {@code parent} and its descendants carry themselves (a popup's content does). */
    private static void repoint(Parent parent) {
        repoint(parent.getStylesheets());
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Parent p) {
                repoint(p);
            }
        }
    }

    // ---- sizes the code fixes ----

    /** {@code px} at the current zoom: a length (a gap, an offset, a font size). */
    public static double px(double px) {
        return px * factor;
    }

    /**
     * {@code px} at the current zoom, for a width or a height: "computed", "the preferred size" and "no
     * limit" ({@code -1}, {@code Double.NEGATIVE_INFINITY}, {@code Double.MAX_VALUE}) are not lengths and come
     * back as they are.
     */
    public static double size(double px) {
        return px < 0 || Double.isInfinite(px) || px == Double.MAX_VALUE ? px : px * factor;
    }

    /** Insets at the current zoom. Use inside {@link #follow}, or through {@link #padding}. */
    public static Insets insets(double top, double right, double bottom, double left) {
        return new Insets(px(top), px(right), px(bottom), px(left));
    }

    /** Insets at the current zoom, the same on every side. */
    public static Insets insets(double all) {
        return new Insets(px(all));
    }

    /**
     * Runs {@code apply} now and again whenever the zoom changes, for as long as {@code node} lives:
     * {@code apply} sets a size from {@link #px}, {@link #size} or {@link #insets}. Calling it again from the
     * same place in the code (a cell's update, a rebuild) replaces the earlier follower, so nothing piles up.
     */
    public static <N extends Node> N follow(N node, Runnable apply) {
        return follow(node, apply.getClass(), apply);
    }

    /** {@link #follow(Node, Runnable)} under an explicit {@code key}: the newest follower of a key wins. */
    public static <N extends Node> N follow(N node, Object key, Runnable apply) {
        register(node, node.getProperties(), key, apply);
        return node;
    }

    /** {@link #follow(Node, Runnable)} for a window: its width, its minimum size. */
    public static <W extends Window> W follow(W window, Runnable apply) {
        register(window, window.getProperties(), apply.getClass(), apply);
        return window;
    }

    private static void register(Object owner, ObservableMap<Object, Object> properties, Object key, Runnable apply) {
        apply.run();
        @SuppressWarnings("unchecked")
        Map<Object, Runnable> followers =
                (Map<Object, Runnable>) properties.computeIfAbsent(FOLLOWERS_KEY, k -> new LinkedHashMap<>());
        synchronized (followers) {
            followers.put(key, apply);
        }
        OWNERS.add(owner);
    }

    /** Sets every size that follows the zoom again. */
    private static void refollow() {
        List<Object> owners;
        synchronized (OWNERS) {
            owners = new ArrayList<>(OWNERS);
        }
        for (Object owner : owners) {
            @Nullable
            ObservableMap<Object, Object> properties = switch (owner) {
                case Node node -> node.getProperties();
                case Window window -> window.getProperties();
                default -> null;
            };
            if (properties == null || !(properties.get(FOLLOWERS_KEY) instanceof Map<?, ?> followers)) {
                continue;
            }
            List<Object> runs;
            synchronized (followers) {
                runs = new ArrayList<>(followers.values());
            }
            for (Object run : runs) {
                try {
                    ((Runnable) run).run();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "A size could not follow the UI zoom", e);
                }
            }
        }
    }

    // ---- the common sizes ----

    public static <R extends Region> R padding(R region, double all) {
        return follow(region, "padding", () -> region.setPadding(insets(all)));
    }

    public static <R extends Region> R padding(R region, double top, double right, double bottom, double left) {
        return follow(region, "padding", () -> region.setPadding(insets(top, right, bottom, left)));
    }

    public static <B extends HBox> B spacing(B box, double px) {
        return follow(box, "spacing", () -> box.setSpacing(px(px)));
    }

    public static <B extends VBox> B spacing(B box, double px) {
        return follow(box, "spacing", () -> box.setSpacing(px(px)));
    }

    /** A grid's horizontal and vertical gaps. */
    public static <G extends GridPane> G gaps(G grid, double hgap, double vgap) {
        return follow(grid, "gaps", () -> {
            grid.setHgap(px(hgap));
            grid.setVgap(px(vgap));
        });
    }

    public static <R extends Region> R prefWidth(R region, double px) {
        return follow(region, "prefWidth", () -> region.setPrefWidth(size(px)));
    }

    public static <R extends Region> R prefHeight(R region, double px) {
        return follow(region, "prefHeight", () -> region.setPrefHeight(size(px)));
    }

    public static <R extends Region> R minWidth(R region, double px) {
        return follow(region, "minWidth", () -> region.setMinWidth(size(px)));
    }

    public static <R extends Region> R minHeight(R region, double px) {
        return follow(region, "minHeight", () -> region.setMinHeight(size(px)));
    }

    public static <R extends Region> R maxWidth(R region, double px) {
        return follow(region, "maxWidth", () -> region.setMaxWidth(size(px)));
    }

    public static <R extends Region> R maxHeight(R region, double px) {
        return follow(region, "maxHeight", () -> region.setMaxHeight(size(px)));
    }

    public static <R extends Region> R prefSize(R region, double width, double height) {
        return follow(region, "prefSize", () -> region.setPrefSize(size(width), size(height)));
    }

    public static <R extends Region> R minSize(R region, double width, double height) {
        return follow(region, "minSize", () -> region.setMinSize(size(width), size(height)));
    }

    public static <R extends Region> R maxSize(R region, double width, double height) {
        return follow(region, "maxSize", () -> region.setMaxSize(size(width), size(height)));
    }

    /**
     * An inline style whose lengths follow the zoom: {@code style} is written at 100%
     * ({@code "-fx-padding: 4 8; -fx-font-size: 11px;"}). Not for the font of content that has its own zoom.
     */
    public static <N extends Node> N style(N node, String style) {
        return follow(node, "style", () -> node.setStyle(UiZoomCss.scaleDeclarations(style, factor)));
    }

    /** {@code style} with its lengths at the current zoom: for a style string assembled where it is used. */
    public static String scaledStyle(String style) {
        return UiZoomCss.scaleDeclarations(style, factor);
    }
}
