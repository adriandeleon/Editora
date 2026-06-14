package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;

import com.editora.web.Browsers.Browser;

import static com.editora.i18n.Messages.tr;

/**
 * The floating "open in browser" control overlaid at the top-right of an HTML editor (mirroring
 * {@link MarkdownViewToggle}). Clicking the globe drops a menu of detected browsers (plus System Default);
 * picking one opens the live preview in it. Detection + opening live in {@code MainController} — this control
 * just renders the button + the menu from the injected browser supplier / pick callback.
 */
public class HtmlPreviewToggle extends Button {

    private final Supplier<List<Browser>> browsers;
    private final Function<Browser, String> labelFor;
    private final Consumer<Browser> onPick;

    public HtmlPreviewToggle(
            Supplier<List<Browser>> browsers, Function<Browser, String> labelFor, Consumer<Browser> onPick) {
        this.browsers = browsers;
        this.labelFor = labelFor;
        this.onPick = onPick;
        setGraphic(Icons.htmlPreview());
        getStyleClass().addAll("html-preview-toggle", "flat");
        setFocusTraversable(false);
        setTooltip(new Tooltip(tr("tooltip.htmlPreview")));
        setOnAction(e -> showMenu());
    }

    private void showMenu() {
        ContextMenu menu = new ContextMenu();
        List<Browser> list = browsers.get();
        if (list == null || list.isEmpty()) {
            MenuItem none = new MenuItem(tr("htmlPreview.noBrowsers"));
            none.setDisable(true);
            menu.getItems().add(none);
        } else {
            for (Browser b : list) {
                MenuItem item = new MenuItem(labelFor.apply(b));
                item.setGraphic(iconFor(b.id()));
                item.setOnAction(e -> onPick.accept(b));
                menu.getItems().add(item);
            }
        }
        menu.show(this, Side.BOTTOM, 0, 0);
    }

    /** The recognizable per-browser glyph; the System Default (and any unknown id) falls back to the globe. */
    private static Node iconFor(String id) {
        return switch (id) {
            case "safari" -> Icons.browserSafari();
            case "chrome" -> Icons.browserChrome();
            case "firefox" -> Icons.browserFirefox();
            case "edge" -> Icons.browserEdge();
            default -> Icons.htmlPreview();
        };
    }
}
