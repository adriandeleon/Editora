package com.editora.ui;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;

/**
 * The response view's tab strip: Body · Headers · Tests · Request · Notes, each with a count where it has one, and
 * the panel's action icons at its right end. Tab focuses the selected tab only; the arrow keys, Home and End move
 * between the tabs. In a narrow panel the icons fold into the "⋯" menu, then the counted tabs show only their count
 * (their name stays in the tooltip and the accessible text) — see {@link HttpPanelLayout#strip}.
 */
final class HttpTabStrip extends HBox {

    /** A count's colour: neutral, all passed (green), a warning (amber) or a failure (red). */
    enum CountStyle {
        NEUTRAL,
        PASSED,
        WARN,
        FAILED
    }

    private static final List<String> COUNT_CLASSES =
            List.of("http-count-passed", "http-count-warn", "http-count-failed");

    private final ToggleGroup group = new ToggleGroup();
    private final Map<HttpResponseTab, ToggleButton> tabs = new EnumMap<>(HttpResponseTab.class);
    private final Map<HttpResponseTab, Label> counts = new EnumMap<>(HttpResponseTab.class);
    private final Map<HttpResponseTab, String> names = new EnumMap<>(HttpResponseTab.class);
    private final Set<HttpResponseTab> available = EnumSet.of(HttpResponseTab.BODY);
    private final HBox tabBox = new HBox(TAB_GAP);
    private final List<? extends Region> icons;
    private final Region more;
    private final Consumer<Boolean> onIconsFolded;
    /** One text node reused to measure tab names (layout runs often). */
    private final Text probe = new Text();

    private HttpPanelLayout.StripFit fit = HttpPanelLayout.StripFit.FULL;

    /** Set while the tabs show only their counts: their padding and the gaps between them shrink too. */
    private static final PseudoClass COMPACT = PseudoClass.getPseudoClass("compact");

    private static final double TAB_GAP = 4;
    /** A tab's padding at full size, kept so the compact padding never feeds back into the fit. */
    private double fullPad;

    /**
     * @param onShown       told whenever the selected tab changes (by the user or by {@link #show})
     * @param onPicked      told when the user picks a tab (click, Enter, Space, arrows)
     * @param icons         the action icons at the right end, which fold into {@code more} when there is no room
     * @param onIconsFolded told when the icons fold (true) into the menu or come back (false)
     */
    HttpTabStrip(
            Map<HttpResponseTab, String> tabNames,
            Consumer<HttpResponseTab> onShown,
            Consumer<HttpResponseTab> onPicked,
            List<? extends Region> icons,
            Region more,
            Consumer<Boolean> onIconsFolded) {
        this.icons = icons;
        this.more = more;
        this.onIconsFolded = onIconsFolded;
        getStyleClass().add("http-tabs");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(HttpPanelLayout.GAP);
        tabBox.getStyleClass().add("http-tab-box");
        tabBox.setAlignment(Pos.BOTTOM_LEFT);
        tabBox.setMinWidth(0);
        for (HttpResponseTab t : HttpResponseTab.values()) {
            String name = tabNames.get(t);
            names.put(t, name);
            Label count = new Label();
            count.getStyleClass().add("http-tab-count");
            count.setMinWidth(Region.USE_PREF_SIZE);
            ToggleButton b = new ToggleButton(name); // the count is its graphic while it has one
            b.getStyleClass().add("http-tab");
            b.setContentDisplay(ContentDisplay.RIGHT);
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setToggleGroup(group);
            b.setUserData(t);
            b.setFocusTraversable(false);
            b.setOnAction(e -> onPicked.accept(t)); // only a click or a key fires it, never show()
            b.addEventFilter(KeyEvent.KEY_PRESSED, e -> key(t, e));
            tabs.put(t, b);
            counts.put(t, count);
            tabBox.getChildren().add(b);
            setShown(b, available.contains(t));
            describe(t, null);
        }
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                group.selectToggle(was); // a click on the selected tab must not leave none selected
                return;
            }
            for (ToggleButton b : tabs.values()) {
                b.setFocusTraversable(b == now); // Tab reaches the selected tab; the arrows move between them
            }
            onShown.accept((HttpResponseTab) now.getUserData());
        });
        group.selectToggle(tabs.get(HttpResponseTab.BODY));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(2);
        actions.getStyleClass().add("http-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getChildren().addAll(icons);
        actions.getChildren().add(more);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().addAll(tabBox, spacer, actions);
    }

    private void key(HttpResponseTab t, KeyEvent e) {
        if (e.isShortcutDown() || e.isAltDown()) {
            return;
        }
        HttpResponseTab to =
                switch (e.getCode()) {
                    case LEFT, KP_LEFT -> HttpResponseTab.step(t, -1, available);
                    case RIGHT, KP_RIGHT -> HttpResponseTab.step(t, 1, available);
                    case HOME -> HttpResponseTab.end(false, available);
                    case END -> HttpResponseTab.end(true, available);
                    default -> null;
                };
        if (to == null) {
            return;
        }
        e.consume();
        if (to != selected()) {
            tabs.get(to).fire(); // a pick by the user, like a click
        }
        tabs.get(to).requestFocus();
    }

    /** The selected tab. */
    HttpResponseTab selected() {
        return group.getSelectedToggle() == null
                ? HttpResponseTab.BODY
                : (HttpResponseTab) group.getSelectedToggle().getUserData();
    }

    /** Selects {@code tab} without counting it as the user's pick. */
    void show(HttpResponseTab tab) {
        ToggleButton b = tabs.get(available.contains(tab) ? tab : HttpResponseTab.BODY);
        if (group.getSelectedToggle() != b) {
            group.selectToggle(b);
        }
    }

    /** The tabs shown now. */
    Set<HttpResponseTab> available() {
        return EnumSet.copyOf(available);
    }

    /** Shows exactly the {@code shown} tabs (Body always); a hidden selected tab gives way to Body. */
    void setAvailable(Set<HttpResponseTab> shown) {
        available.clear();
        available.add(HttpResponseTab.BODY);
        available.addAll(shown);
        tabs.forEach((t, b) -> setShown(b, available.contains(t)));
        if (!available.contains(selected())) {
            show(HttpResponseTab.BODY);
        }
        requestLayout();
    }

    /**
     * Sets {@code tab}'s count ({@code null} hides it), its colour, and the {@code detail} the tooltip and a screen
     * reader give with the tab's name ("6 headers", "✓ 2 passed · ✗ 1 failed").
     */
    void setCount(HttpResponseTab tab, String count, CountStyle style, String detail) {
        Label l = counts.get(tab);
        boolean on = count != null && !count.isEmpty();
        l.setText(on ? count : "");
        ToggleButton b = tabs.get(tab);
        if (b.getGraphic() != (on ? l : null)) {
            b.setGraphic(on ? l : null);
        }
        l.getStyleClass().removeAll(COUNT_CLASSES);
        switch (style == null ? CountStyle.NEUTRAL : style) {
            case PASSED -> l.getStyleClass().add("http-count-passed");
            case WARN -> l.getStyleClass().add("http-count-warn");
            case FAILED -> l.getStyleClass().add("http-count-failed");
            case NEUTRAL -> {}
        }
        describe(tab, on ? detail : null);
        requestLayout();
    }

    private void describe(HttpResponseTab tab, String detail) {
        String name = names.get(tab);
        String text = detail == null || detail.isBlank() ? name : name + " — " + detail;
        ToggleButton b = tabs.get(tab);
        b.setAccessibleText(text);
        b.setTooltip(new Tooltip(text));
    }

    /** The tab's button. */
    ToggleButton button(HttpResponseTab tab) {
        return tabs.get(tab);
    }

    /** What the tab shows: its name and count ("Tests 2/3"), only the count when narrow, null when hidden. */
    String shownTextForTest(HttpResponseTab tab) {
        ToggleButton b = tabs.get(tab);
        if (!b.isVisible()) {
            return null;
        }
        String count = hasCount(tab) ? counts.get(tab).getText() : "";
        if (b.getContentDisplay() == ContentDisplay.GRAPHIC_ONLY) {
            return count;
        }
        return count.isEmpty() ? b.getText() : b.getText() + " " + count;
    }

    /** The count's colour, or null when it has none. Test accessor. */
    CountStyle countStyleForTest(HttpResponseTab tab) {
        if (!hasCount(tab)) {
            return null;
        }
        Label c = counts.get(tab);
        if (c.getStyleClass().contains("http-count-failed")) {
            return CountStyle.FAILED;
        }
        if (c.getStyleClass().contains("http-count-warn")) {
            return CountStyle.WARN;
        }
        return c.getStyleClass().contains("http-count-passed") ? CountStyle.PASSED : CountStyle.NEUTRAL;
    }

    HttpPanelLayout.StripFit fitForTest() {
        return fit;
    }

    @Override
    protected void layoutChildren() {
        fitToWidth();
        super.layoutChildren();
    }

    @Override
    protected double computeMinWidth(double height) {
        return snappedLeftInset() + more.prefWidth(-1) + snappedRightInset();
    }

    /** Folds the icons into the menu, then the counted tabs to their counts, when the strip has no room for them. */
    private void fitToWidth() {
        double room = getWidth() - snappedLeftInset() - snappedRightInset();
        if (room <= 0) {
            return;
        }
        double full = 0;
        double counted = 0;
        int shown = 0;
        for (HttpResponseTab t : HttpResponseTab.values()) {
            if (!available.contains(t)) {
                continue;
            }
            ToggleButton b = tabs.get(t);
            Label c = counts.get(t);
            fullPad = Math.max(fullPad, b.snappedLeftInset() + b.snappedRightInset());
            double pad = fullPad;
            probe.setFont(b.getFont());
            probe.setText(b.getText());
            double name = Math.ceil(probe.getLayoutBounds().getWidth()) + 2;
            double count = hasCount(t) ? c.prefWidth(-1) : 0;
            full += pad + name + (count > 0 ? b.getGraphicTextGap() + count : 0);
            counted += pad + (count > 0 ? count : name);
            shown++;
        }
        double gaps = Math.max(0, shown - 1) * TAB_GAP;
        full += gaps;
        counted += gaps;
        double iconsWidth = 0;
        for (Region r : icons) {
            iconsWidth += r.prefWidth(-1) + 2;
        }
        HttpPanelLayout.StripFit now =
                HttpPanelLayout.strip(room, full, counted, iconsWidth, more.prefWidth(-1) + HttpPanelLayout.GAP);
        boolean folded = now != HttpPanelLayout.StripFit.FULL;
        for (Region r : icons) {
            setShown(r, !folded);
        }
        for (HttpResponseTab t : HttpResponseTab.values()) {
            boolean countOnly = now == HttpPanelLayout.StripFit.COUNTS && hasCount(t);
            ContentDisplay want = countOnly ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.RIGHT;
            if (tabs.get(t).getContentDisplay() != want) {
                tabs.get(t).setContentDisplay(want);
            }
        }
        boolean compact = now == HttpPanelLayout.StripFit.COUNTS;
        pseudoClassStateChanged(COMPACT, compact);
        tabBox.setSpacing(compact ? 0 : TAB_GAP);
        if (now != fit) {
            boolean wasFolded = fit != HttpPanelLayout.StripFit.FULL;
            fit = now;
            if (wasFolded != folded) {
                onIconsFolded.accept(folded);
            }
        }
    }

    private boolean hasCount(HttpResponseTab tab) {
        return tabs.get(tab).getGraphic() != null;
    }

    private static void setShown(Node n, boolean on) {
        if (n.isVisible() != on) {
            n.setVisible(on);
        }
        if (n.isManaged() != on) {
            n.setManaged(on);
        }
    }
}
