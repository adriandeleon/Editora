package com.editora.editor;

import java.util.List;

import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

import org.jspecify.annotations.Nullable;

/**
 * The one place a control gets its name: its tooltip and its accessible text are set together, from one
 * string, so a screen reader says what a sighted user reads. An icon-only button is named here and nowhere
 * else ({@code com.editora.ui.Icons.name} is this, for the panels).
 *
 * <p>A control's tooltip is built when the pointer first enters the control ({@link #tip}), not with the
 * control: a {@link Tooltip} is a popup window with its own scene, and a window used to build a few hundred of
 * them before its first paint, almost none of which is ever hovered. Until then the text is kept, and given to
 * a screen reader as the control's accessible help, which is what the control would read from its tooltip.
 */
public final class ControlNames {

    /**
     * How long the pointer rests before a tooltip shows, for every tooltip made here, by {@link LazyTooltip}
     * and by the few that build their own. It is set in code, tooltip by tooltip: a {@code -fx-show-delay} in
     * the stylesheet does not survive the precompiled {@code .bss} form (the binary reader drops a duration's
     * converter), so the rule would hold in a development run and do nothing in a packaged one.
     */
    public static final Duration TOOLTIP_DELAY = Duration.millis(400);

    /** The key, in a control's properties, of its {@link PendingTip}. */
    private static final String TIP_KEY = "editora.controlNames.tip";

    /** The key, in a tab's properties, of its tooltip text while the tooltip is not built. */
    private static final String TAB_TIP_KEY = "editora.controlNames.tabTip";

    /** The key that marks a tab (watched for its tab pane) or a tab pane (watched for the pointer). */
    private static final String TAB_HOOK_KEY = "editora.controlNames.tabTipHook";

    /**
     * The tab panes' one handler: the pointer entering a tab's header builds that tab's tooltip. The header (a
     * skin's node) carries its tab under {@code Tab.class} in its properties.
     */
    private static final EventHandler<MouseEvent> TAB_ENTERED = e -> {
        Node n = e.getTarget() instanceof Node target ? target : null;
        for (; n != null && n != e.getSource(); n = n.getParent()) {
            if (n.hasProperties() && n.getProperties().get(Tab.class) instanceof Tab tab) {
                hover(() -> builtTooltip(tab));
                return;
            }
        }
    };

    /** Tooltips built so far, on the FX thread: the test seam for "a new window builds none". */
    private static int built;

    /** Where each tooltip was built, while a test listens ({@link #traceTooltips}). */
    private static @Nullable List<String> trace;

    /** Whether a tooltip being built now is built because the pointer entered its node. */
    private static boolean hovering;

    private ControlNames() {}

    /**
     * A tooltip that shows after {@link #TOOLTIP_DELAY}: every tooltip in the app is made here (or by
     * {@link #name} and {@link #tip}), never with {@code new Tooltip}, so none answers faster or slower than its
     * neighbors. A control's tooltip goes through {@link #tip} instead, which builds it on first hover.
     */
    public static Tooltip tooltip(@Nullable String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(TOOLTIP_DELAY);
        count();
        return tooltip;
    }

    /** As {@link #tooltip(String)}, for a tooltip whose text is set later. */
    public static Tooltip tooltip() {
        Tooltip tooltip = new Tooltip();
        tooltip.setShowDelay(TOOLTIP_DELAY);
        count();
        return tooltip;
    }

    /** Sets {@code control}'s tooltip and accessible text to {@code name}. Call it again when the name changes. */
    public static <C extends Control> C name(C control, String name) {
        control.setAccessibleText(name);
        // A name that is a sentence (the diff bar's, a toggle that explains itself) wraps instead of running
        // the width of the screen; a short one is unaffected.
        return tip(control, name, true);
    }

    /**
     * Sets {@code control}'s tooltip text: {@code null} or blank removes the tooltip. The {@link Tooltip} itself
     * is built when the pointer first enters the control; until then a screen reader reads the text as the
     * control's accessible help (unless the control has help of its own), as it would read the tooltip's.
     * Call it again when the text changes; it updates a tooltip already built. Read the text back with
     * {@link #tipText}, not {@code getTooltip()}, which is {@code null} until the first hover.
     */
    public static <C extends Control> C tip(C control, @Nullable String text) {
        return tip(control, text, false);
    }

    private static <C extends Control> C tip(C control, @Nullable String text, boolean wrap) {
        boolean blank = text == null || text.isBlank();
        PendingTip pending = (PendingTip) control.getProperties().get(TIP_KEY);
        if (pending == null) {
            if (blank && control.getTooltip() == null) {
                return control;
            }
            pending = new PendingTip(control);
            control.getProperties().put(TIP_KEY, pending);
        }
        pending.wrap |= wrap;
        pending.text = blank ? null : text;
        pending.mirrorHelp();
        Tooltip tooltip = control.getTooltip();
        if (blank) {
            pending.disarm();
            control.setTooltip(null);
        } else if (tooltip != null) {
            tooltip.setText(text);
        } else {
            pending.arm();
        }
        return control;
    }

    /**
     * As {@link #tip(Control, String)}, for a tab: the tooltip is built when the pointer first enters the tab's
     * header. Read the text back with {@link #tipText(Tab)}.
     */
    public static void tip(Tab tab, @Nullable String text) {
        Tooltip tooltip = tab.getTooltip();
        if (text == null || text.isBlank()) {
            tab.getProperties().remove(TAB_TIP_KEY);
            tab.setTooltip(null);
        } else if (tooltip != null) {
            tooltip.setText(text);
        } else {
            tab.getProperties().put(TAB_TIP_KEY, text);
            if (tab.getProperties().putIfAbsent(TAB_HOOK_KEY, Boolean.TRUE) == null) {
                tab.tabPaneProperty().addListener((o, was, pane) -> hookTabPane(pane));
            }
            hookTabPane(tab.getTabPane());
        }
    }

    /** {@code tab}'s tooltip text, built or not yet; {@code null} when it has none. */
    public static @Nullable String tipText(Tab tab) {
        Tooltip tooltip = tab.getTooltip();
        return tooltip != null
                ? tooltip.getText()
                : (String) tab.getProperties().get(TAB_TIP_KEY);
    }

    private static void hookTabPane(@Nullable TabPane pane) {
        if (pane != null && pane.getProperties().putIfAbsent(TAB_HOOK_KEY, Boolean.TRUE) == null) {
            pane.addEventHandler(MouseEvent.MOUSE_ENTERED_TARGET, TAB_ENTERED);
        }
    }

    /** Builds {@code tab}'s tooltip now if {@link #tip(Tab, String)} left one to build; returns it or null. */
    public static @Nullable Tooltip builtTooltip(Tab tab) {
        if (tab.getProperties().remove(TAB_TIP_KEY) instanceof String text && tab.getTooltip() == null) {
            tab.setTooltip(tooltip(text));
        }
        return tab.getTooltip();
    }

    /** {@code control}'s tooltip text, built or not yet ({@link #tip}); {@code null} when it has none. */
    public static @Nullable String tipText(Control control) {
        Tooltip tooltip = control.getTooltip();
        if (tooltip != null) {
            return tooltip.getText();
        }
        PendingTip pending = (PendingTip) control.getProperties().get(TIP_KEY);
        return pending == null ? null : pending.text;
    }

    /** Builds {@code control}'s tooltip now if {@link #tip} left one to build on hover; returns it or null. */
    public static @Nullable Tooltip builtTooltip(Control control) {
        PendingTip pending = (PendingTip) control.getProperties().get(TIP_KEY);
        if (pending != null && control.getTooltip() == null) {
            pending.build();
        }
        return control.getTooltip();
    }

    /** How many tooltips have been built so far (test seam). */
    public static int tooltipsBuilt() {
        return built;
    }

    /**
     * While {@code sink} is non-null, records where each tooltip is built (test seam); one built because the
     * pointer entered its node (which a window shown under the pointer gets too) starts with {@code "hover "}.
     */
    public static void traceTooltips(@Nullable List<String> sink) {
        trace = sink;
    }

    /** Runs {@code build}, a tooltip built because the pointer entered its node. */
    static void hover(Runnable build) {
        boolean was = hovering;
        hovering = true;
        try {
            build.run();
        } finally {
            hovering = was;
        }
    }

    private static void count() {
        built++;
        List<String> sink = trace;
        if (sink != null) {
            sink.add((hovering ? "hover " : "")
                    + StackWalker.getInstance()
                            .walk(frames -> frames.skip(2)
                                    .filter(f -> f.getClassName().startsWith("com.editora.")
                                            && !f.getClassName().startsWith(ControlNames.class.getName()))
                                    .limit(3)
                                    .map(StackWalker.StackFrame::toString)
                                    .toList())
                            .toString());
        }
    }

    /** A control's tooltip text, kept until the pointer first enters the control and the tooltip is built. */
    private static final class PendingTip implements EventHandler<MouseEvent> {
        private final Control control;
        private @Nullable String text;
        private boolean wrap;
        private boolean armed;

        /** The accessible help this set from the text, to tell it from help the control was given otherwise. */
        private @Nullable String mirroredHelp;

        PendingTip(Control control) {
            this.control = control;
        }

        @Override
        public void handle(MouseEvent e) {
            // Early enough: the tooltip's own timer starts on the MOUSE_MOVED events that follow.
            hover(this::build);
        }

        void arm() {
            if (!armed) {
                armed = true;
                control.addEventHandler(MouseEvent.MOUSE_ENTERED, this);
            }
        }

        void disarm() {
            if (armed) {
                armed = false;
                control.removeEventHandler(MouseEvent.MOUSE_ENTERED, this);
            }
        }

        void build() {
            disarm();
            String value = text;
            if (value == null || control.getTooltip() != null) {
                return;
            }
            Tooltip tooltip = tooltip(value);
            if (wrap) {
                tooltip.setWrapText(true);
                UiZoom.follow(control, "tooltipWidth", () -> tooltip.setMaxWidth(UiZoom.px(360)));
            }
            control.setTooltip(tooltip);
            unmirrorHelp();
        }

        /**
         * While the tooltip is not built, puts its text in the accessible help, where a control with no help of
         * its own would have read its tooltip's text; help the control was given otherwise is left alone.
         */
        void mirrorHelp() {
            String help = control.getAccessibleHelp();
            boolean free = help == null || help.isEmpty() || help.equals(mirroredHelp);
            if (!free || control.getTooltip() != null) {
                mirroredHelp = null;
                return;
            }
            mirroredHelp = text;
            control.setAccessibleHelp(text);
        }

        /** Once the tooltip is built it supplies the help itself: the mirrored copy is taken back out. */
        void unmirrorHelp() {
            if (mirroredHelp != null && mirroredHelp.equals(control.getAccessibleHelp())) {
                control.setAccessibleHelp(null);
            }
            mirroredHelp = null;
        }
    }

    /**
     * A label that names {@code control} for a screen reader ({@code labelFor}), as a form's row label does: a
     * field, a list or a box beside a label reads as that label (ACC-01, Pauta 6.2.0).
     */
    public static Label label(String text, Node control) {
        Label label = new Label(text);
        label.setLabelFor(control);
        return label;
    }

    /**
     * {@code title} followed by the chord the active keymap binds to {@code commandId}, in parentheses, or alone
     * when the command is unbound. The chord is the one the right-click menus show ({@link MenuChords}).
     */
    public static String withChord(String title, String commandId) {
        String chord = MenuChords.chordOf(commandId);
        return chord == null || chord.isBlank() ? title : title + " (" + chord + ")";
    }
}
