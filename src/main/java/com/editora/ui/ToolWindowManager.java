package com.editora.ui;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Settings;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Lays out IntelliJ-style left/right/bottom stripes around the editor area, manages registered
 * tool windows (one open per side, toggled via stripe buttons), and persists open state +
 * divider positions to settings.json.
 */
public class ToolWindowManager {

    private static final PseudoClass OPEN = PseudoClass.getPseudoClass("open");

    private final ConfigManager config;
    private final KeymapManager keymap;

    private final VBox leftStripe = new VBox();
    private final VBox rightStripe = new VBox();
    private final HBox bottomStripe = new HBox();

    private final SplitPane hSplit = new SplitPane();
    private final SplitPane vSplit = new SplitPane();

    private final Map<String, ToolWindow> byId = new LinkedHashMap<>();
    private final Map<ToolWindow.Side, ToolWindow> openBySide = new HashMap<>();
    private final Map<ToolWindow, Button> stripeButtons = new HashMap<>();
    private final Map<ToolWindow, Region> panels = new HashMap<>();

    public ToolWindowManager(BorderPane workspace, Node editorArea, ConfigManager config, KeymapManager keymap) {
        this.config = config;
        this.keymap = keymap;

        leftStripe.getStyleClass().addAll("tool-stripe", "tool-stripe-vertical", "tool-stripe-left");
        rightStripe.getStyleClass().addAll("tool-stripe", "tool-stripe-vertical", "tool-stripe-right");
        bottomStripe.getStyleClass().addAll("tool-stripe", "tool-stripe-horizontal", "tool-stripe-bottom");
        leftStripe.setAlignment(Pos.TOP_CENTER);
        rightStripe.setAlignment(Pos.TOP_CENTER);
        bottomStripe.setAlignment(Pos.CENTER_LEFT);

        hSplit.setOrientation(Orientation.HORIZONTAL);
        hSplit.getItems().add(editorArea);

        vSplit.setOrientation(Orientation.VERTICAL);
        vSplit.getItems().add(hSplit);

        workspace.setLeft(leftStripe);
        workspace.setRight(rightStripe);
        workspace.setBottom(bottomStripe);
        workspace.setCenter(vSplit);
        updateStripeVisibility();
    }

    public void register(ToolWindow tw) {
        byId.put(tw.getId(), tw);
        Button button = new Button();
        button.setGraphic(tw.createIcon());
        button.getStyleClass().addAll("tool-stripe-button", "flat");
        button.setTooltip(new Tooltip(tooltipFor(tw)));
        button.setOnAction(e -> toggle(tw));
        stripeButtons.put(tw, button);
        if (isVisible(tw)) {
            stripeFor(currentSide(tw)).getChildren().add(button);
        }
        updateStripeVisibility();
    }

    /** True if this tool window's stripe button should be shown. Defaults to visible. */
    public boolean isVisible(ToolWindow tw) {
        Boolean v = config.getSettings().getToolWindowVisible().get(tw.getId());
        return v == null || v;
    }

    /** Hide/show the tool window's stripe button. When hidden, also closes it if open. */
    public void setVisible(ToolWindow tw, boolean visible) {
        if (isVisible(tw) == visible) {
            return;
        }
        Button button = stripeButtons.get(tw);
        if (!visible) {
            if (openBySide.get(currentSide(tw)) == tw) {
                close(tw);
            }
            if (button != null) {
                stripeFor(currentSide(tw)).getChildren().remove(button);
            }
        } else if (button != null) {
            stripeFor(currentSide(tw)).getChildren().add(button);
        }
        config.getSettings().getToolWindowVisible().put(tw.getId(), visible);
        updateStripeVisibility();
        config.save();
    }

    public Collection<ToolWindow> getRegisteredToolWindows() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /** The currently open tool windows, ordered by side (left, bottom, right), for focus cycling. */
    public java.util.List<ToolWindow> getOpenToolWindows() {
        java.util.List<ToolWindow> open = new java.util.ArrayList<>();
        for (ToolWindow.Side side : ToolWindow.Side.values()) {
            ToolWindow tw = openBySide.get(side);
            if (tw != null) {
                open.add(tw);
            }
        }
        return open;
    }

    /** Tooltip text: title plus the chord for the tool window's command, if one is bound. */
    private String tooltipFor(ToolWindow tw) {
        String cmd = tw.getCommandId();
        if (cmd == null) {
            return tw.getTitle();
        }
        for (Map.Entry<String, String> e : keymap.bindings().entrySet()) {
            if (cmd.equals(e.getValue())) {
                return tw.getTitle() + " (" + e.getKey() + ")";
            }
        }
        return tw.getTitle();
    }

    /** The side this tool window is currently assigned to (settings override, falling back to the registered default). */
    public ToolWindow.Side currentSide(ToolWindow tw) {
        String stored = config.getSettings().getToolWindowSides().get(tw.getId());
        if (stored != null) {
            try {
                return ToolWindow.Side.valueOf(stored);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return tw.getSide();
    }

    /** Moves the tool window's stripe button to a different side; closes it first if it was open. */
    public void setSide(ToolWindow tw, ToolWindow.Side newSide) {
        ToolWindow.Side oldSide = currentSide(tw);
        if (oldSide == newSide) {
            return;
        }
        if (openBySide.get(oldSide) == tw) {
            close(tw);
        }
        Button button = stripeButtons.get(tw);
        if (button != null && isVisible(tw)) {
            stripeFor(oldSide).getChildren().remove(button);
            stripeFor(newSide).getChildren().add(button);
        }
        config.getSettings().getToolWindowSides().put(tw.getId(), newSide.name());
        updateStripeVisibility();
        config.save();
    }

    private void updateStripeVisibility() {
        setStripeShown(leftStripe);
        setStripeShown(rightStripe);
        setStripeShown(bottomStripe);
    }

    private static void setStripeShown(Pane stripe) {
        boolean shown = !stripe.getChildren().isEmpty();
        stripe.setVisible(shown);
        stripe.setManaged(shown);
    }

    /** Opens any tool windows the settings file says were open last time. */
    public void restore() {
        Settings s = config.getSettings();
        openById(s.getOpenLeftToolWindow());
        openById(s.getOpenRightToolWindow());
        openById(s.getOpenBottomToolWindow());
    }

    public void toggle(ToolWindow tw) {
        ToolWindow.Side side = currentSide(tw);
        if (openBySide.get(side) == tw) {
            close(tw);
        } else {
            open(tw);
        }
    }

    public void open(ToolWindow tw) {
        ToolWindow.Side side = currentSide(tw);
        ToolWindow current = openBySide.get(side);
        if (current == tw) {
            return;
        }
        if (current != null) {
            close(current);
        }
        ToolWindowPanel panel = new ToolWindowPanel(tw, () -> close(tw));
        panels.put(tw, panel);
        openBySide.put(side, tw);
        switch (side) {
            case LEFT -> {
                hSplit.getItems().add(0, panel);
                double pos = config.getSettings().getLeftDividerPosition();
                Platform.runLater(() -> hSplit.setDividerPosition(0, pos));
            }
            case RIGHT -> {
                hSplit.getItems().add(panel);
                double pos = config.getSettings().getRightDividerPosition();
                int dividerIdx = hSplit.getItems().size() - 2;
                Platform.runLater(() -> hSplit.setDividerPosition(dividerIdx, pos));
            }
            case BOTTOM -> {
                vSplit.getItems().add(panel);
                double pos = config.getSettings().getBottomDividerPosition();
                Platform.runLater(() -> vSplit.setDividerPosition(0, pos));
            }
        }
        stripeButtons.get(tw).pseudoClassStateChanged(OPEN, true);
        persist();
    }

    public void close(ToolWindow tw) {
        Region panel = panels.remove(tw);
        if (panel == null) {
            return;
        }
        ToolWindow.Side side = currentSide(tw);
        Settings settings = config.getSettings();
        switch (side) {
            case LEFT -> {
                int idx = hSplit.getItems().indexOf(panel);
                if (idx >= 0 && idx < hSplit.getDividers().size()) {
                    settings.setLeftDividerPosition(hSplit.getDividers().get(idx).getPosition());
                }
                hSplit.getItems().remove(panel);
            }
            case RIGHT -> {
                int idx = hSplit.getItems().indexOf(panel);
                if (idx > 0 && idx - 1 < hSplit.getDividers().size()) {
                    settings.setRightDividerPosition(hSplit.getDividers().get(idx - 1).getPosition());
                }
                hSplit.getItems().remove(panel);
            }
            case BOTTOM -> {
                if (!vSplit.getDividers().isEmpty()) {
                    settings.setBottomDividerPosition(vSplit.getDividers().get(0).getPosition());
                }
                vSplit.getItems().remove(panel);
            }
        }
        openBySide.remove(side, tw);
        stripeButtons.get(tw).pseudoClassStateChanged(OPEN, false);
        persist();
    }

    private void openById(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        ToolWindow tw = byId.get(id);
        if (tw != null && isVisible(tw)) {
            open(tw);
        }
    }

    private Pane stripeFor(ToolWindow.Side side) {
        return switch (side) {
            case LEFT -> leftStripe;
            case RIGHT -> rightStripe;
            case BOTTOM -> bottomStripe;
        };
    }

    private void persist() {
        Settings s = config.getSettings();
        s.setOpenLeftToolWindow(idOf(openBySide.get(ToolWindow.Side.LEFT)));
        s.setOpenRightToolWindow(idOf(openBySide.get(ToolWindow.Side.RIGHT)));
        s.setOpenBottomToolWindow(idOf(openBySide.get(ToolWindow.Side.BOTTOM)));
        config.save();
    }

    private static String idOf(ToolWindow tw) {
        return tw == null ? "" : tw.getId();
    }
}
