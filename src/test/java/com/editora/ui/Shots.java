package com.editora.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * The screenshot harness (DOCS-18, Pauta 6.0.1): drives the real Editora window headlessly from a command script
 * ({@link ShotScript}) and writes each {@code shot}/{@code winshot} at twice the scene's size ({@link
 * FxTestSupport#SHOT_SCALE}). It lives with the tests because it builds the window through {@link FxWindowFixture},
 * the production boot path the FX tests use; it is not a test and never runs in the suite. {@code
 * scripts/screenshots/Shoot.java} runs it for the website, {@link ShotsFxTest} once behind {@code
 * -Deditora.test.shots=<dir>}; docs/screenshots.md has the whole procedure.
 *
 * <pre>
 *   java … com.editora.ui.Shots CONFIG_DIR SCRIPT [name=value …]
 * </pre>
 *
 * Every wait is on the FX thread's pulses or on the state itself, never a sleep (QUAL-07): {@code wait N} lets
 * about N ms of pulses (60 a second) go by, which is the clock Editora's debounces and animations run on.
 */
public final class Shots {

    /** Run with -Deditora.shots.homeAlias=/home/ada: the scratch home's path reads as this in every shot. */
    private static final String HOME_ALIAS = System.getProperty("editora.shots.homeAlias", "");

    private final Path cfg;
    private final List<FxWindowFixture> fixtures = new ArrayList<>();
    private FxWindowFixture fx;
    private Stage stage;
    private MainController ctl;
    private Window keyWindow;
    private final double scale = FxTestSupport.SHOT_SCALE;
    private final java.util.Map<String, String> seed = new java.util.LinkedHashMap<>();

    /** A picture written: its file, its size in pixels and the size of the scene (or window) it shows. */
    record Written(Path file, int width, int height, double sceneWidth, double sceneHeight) {}

    /** Every picture this run wrote, in order. */
    final List<Written> written = new ArrayList<>();

    Shots(Path cfg) {
        this.cfg = cfg;
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("usage: Shots CONFIG_DIR SCRIPT [name=value ...]");
            Runtime.getRuntime().halt(2);
        }
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (int i = 2; i < a.length; i++) {
            int eq = a[i].indexOf('=');
            values.put(a[i].substring(0, eq), a[i].substring(eq + 1));
        }
        int failures = 1;
        try {
            failures = new Shots(Path.of(a[0])).run(Files.readAllLines(Path.of(a[1])), values);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.out.flush();
        // The window's background threads (file watchers, servers) are not daemons: end the JVM outright.
        Runtime.getRuntime().halt(failures == 0 ? 0 : 1);
    }

    /**
     * Runs {@code lines} (variables from {@code values}; {@code theme} there seeds the UI and editor theme) and
     * returns how many commands failed. A failed command is reported and the script goes on, so one missing
     * button costs one picture, not the run.
     */
    int run(List<String> lines, java.util.Map<String, String> values) throws Exception {
        List<ShotScript.Step> steps = ShotScript.parse(lines, values);
        Files.createDirectories(cfg);
        if (values.containsKey("theme")) {
            seed.put("theme", "\"" + values.get("theme") + "\"");
            seed.put("editorTheme", "\"" + values.get("theme") + "\"");
        }
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            try {
                var m = com.editora.App.class.getDeclaredMethod("installUiFont");
                m.setAccessible(true);
                m.invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        boolean zen = false, expert = false, simple = false;
        int w = 1300, h = 800;
        int failures = 0;
        try {
            for (ShotScript.Step step : steps) {
                String op = step.op();
                String arg = step.arg();
                String line = op + (arg.isEmpty() ? "" : " " + arg);
                try {
                    switch (op) {
                        case "set" -> {
                            String[] kv = arg.split("\\s+", 2);
                            seed.put(kv[0], kv[1]);
                        }
                        case "settings" -> {
                            FxTestSupport.runOnFx(() -> {
                                for (Window win : Window.getWindows()) {
                                    if (win == stage || !win.isShowing() || win.getScene() == null) continue;
                                    for (Node n : win.getScene().getRoot().lookupAll(".list-view")) {
                                        if (n instanceof javafx.scene.control.ListView<?> lv) {
                                            for (int i = 0; i < lv.getItems().size(); i++) {
                                                Object it = lv.getItems().get(i);
                                                if (it instanceof Enum<?> e
                                                        && e.name().equals(arg)) {
                                                    lv.getSelectionModel().select(i);
                                                    lv.scrollTo(Math.max(0, i - 8));
                                                }
                                            }
                                        }
                                    }
                                }
                            });
                            settle(900);
                        }
                        case "winsize" -> {
                            String[] z = arg.split("\\s+");
                            FxTestSupport.runOnFx(() -> {
                                for (Window win : Window.getWindows()) {
                                    if (win == stage || !win.isShowing() || !(win instanceof Stage)) continue;
                                    win.setWidth(Double.parseDouble(z[0]));
                                    win.setHeight(Double.parseDouble(z[1]));
                                }
                            });
                            settle(700);
                        }
                        case "winpos" -> {
                            String[] z = arg.split("\\s+");
                            FxTestSupport.runOnFx(() -> {
                                for (Window win : Window.getWindows()) {
                                    if (win == stage || !win.isShowing() || !(win instanceof Stage)) continue;
                                    win.setX(Double.parseDouble(z[0]));
                                    win.setY(Double.parseDouble(z[1]));
                                }
                            });
                            settle(500);
                        }
                        case "click" -> {
                            boolean[] hit = new boolean[1];
                            FxTestSupport.runOnFx(() -> {
                                for (Window win : Window.getWindows()) {
                                    if (!win.isShowing() || win.getScene() == null) continue;
                                    for (Node n : win.getScene().getRoot().lookupAll("*")) {
                                        if (!hit[0]
                                                && n instanceof javafx.scene.control.ButtonBase b
                                                && arg.equals(b.getText())
                                                && b.isVisible()) {
                                            b.fire();
                                            hit[0] = true;
                                        }
                                    }
                                }
                            });
                            if (!hit[0]) System.out.println("  !! no button: " + arg);
                            settle(1200);
                        }
                        case "focuseditor" -> {
                            FxTestSupport.runOnFx(() -> {
                                try {
                                    Node target = null;
                                    for (Node n : stage.getScene().getRoot().lookupAll("*")) {
                                        if (n instanceof org.fxmisc.richtext.CodeArea && n.isVisible()) target = n;
                                    }
                                    Node owner = stage.getScene().getFocusOwner();
                                    if (owner instanceof org.fxmisc.richtext.CodeArea) target = owner;
                                    target.requestFocus();
                                    var m = Node.class.getDeclaredMethod("setFocused", boolean.class);
                                    m.setAccessible(true);
                                    m.invoke(target, true);
                                    System.out.println("  focused=" + target.isFocused());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
                            settle(300);
                        }
                        case "focuslast" -> {
                            FxTestSupport.runOnFx(() -> {
                                Node target = null;
                                for (Node n : stage.getScene().getRoot().lookupAll(".text-input")) {
                                    if (!n.getStyleClass().contains("project-filter")
                                            && n.isVisible()
                                            && n.getScene() != null
                                            && n.localToScene(n.getBoundsInLocal())
                                                            .getWidth()
                                                    > 0) {
                                        target = n;
                                    }
                                }
                                target.requestFocus();
                                System.out.println("  focus -> " + target + " owner="
                                        + stage.getScene().getFocusOwner());
                            });
                            settle(300);
                        }
                        case "runfast" -> {
                            FxTestSupport.runOnFx(() -> {
                                com.editora.command.CommandRegistry reg = FxTestSupport.field(ctl, "registry");
                                reg.run(arg);
                            });
                            settle(400);
                            FxTestSupport.runOnFx(() -> {
                                for (Window win : Window.getWindows()) {
                                    System.out.println("  window "
                                            + win.getClass().getSimpleName() + " showing=" + win.isShowing());
                                }
                            });
                        }
                        case "focusprompt" -> {
                            FxTestSupport.runOnFx(() -> {
                                for (Node n : stage.getScene().getRoot().lookupAll(".text-field")) {
                                    if (n instanceof javafx.scene.control.TextField tf
                                            && n.isVisible()
                                            && tf.getPromptText() != null
                                            && tf.getPromptText().startsWith(arg)) {
                                        tf.requestFocus();
                                        System.out.println("  focus -> " + tf.getPromptText());
                                    }
                                }
                            });
                            settle(300);
                        }
                        case "menu" -> {
                            FxTestSupport.runOnFx(() -> {
                                for (Node n : stage.getScene().getRoot().lookupAll(".menu-bar")) {
                                    if (n instanceof javafx.scene.control.MenuBar mb) {
                                        for (javafx.scene.control.Menu m : mb.getMenus()) {
                                            if (arg.equals(m.getText())) m.show();
                                        }
                                    }
                                }
                            });
                            settle(800);
                        }
                        case "winshot" -> winshot(arg);
                        case "choose" -> { // choose <css> <n> <item>: select an item of the nth ChoiceBox matching css
                            String[] z = arg.split("\\s+");
                            FxTestSupport.runOnFx(() -> {
                                int i = 0;
                                for (Window win : Window.getWindows()) {
                                    if (!win.isShowing() || win.getScene() == null) continue;
                                    for (Node n : win.getScene().getRoot().lookupAll(z[0])) {
                                        if (n instanceof javafx.scene.control.ChoiceBox<?> cb
                                                && n.isVisible()
                                                && i++ == Integer.parseInt(z[1])) {
                                            cb.getSelectionModel().select(Integer.parseInt(z[2]));
                                            System.out.println("  choose -> " + cb.getValue());
                                        }
                                    }
                                }
                            });
                            settle(800);
                        }
                        case "closewins" -> {
                            FxTestSupport.runOnFx(() -> {
                                for (Window win : List.copyOf(Window.getWindows())) {
                                    if (win != stage && win.isShowing()) win.hide();
                                }
                            });
                            settle(500);
                        }
                        case "mode" -> {
                            zen = arg.contains("zen");
                            expert = arg.contains("expert");
                            simple = arg.contains("simple");
                        }
                        case "size" -> {
                            String[] s = arg.split("\\s+");
                            w = Integer.parseInt(s[0]);
                            h = Integer.parseInt(s[1]);
                            if (stage != null) {
                                int fw = w, fh = h;
                                FxTestSupport.runOnFx(() -> {
                                    stage.setWidth(fw);
                                    stage.setHeight(fh);
                                });
                                settle(600);
                            }
                        }
                        case "start" -> {
                            if (seed.containsKey("uiLanguage")) {
                                String lang = seed.get("uiLanguage").replace("\"", "");
                                FxTestSupport.runOnFx(() -> com.editora.i18n.Messages.init(lang));
                            }
                            if (!seed.isEmpty() && !Files.exists(cfg.resolve("settings.json"))) {
                                StringBuilder sb = new StringBuilder(
                                        "{\n  \"schemaVersion\" : " + com.editora.config.Settings.SCHEMA_VERSION);
                                seed.forEach((k, v) -> sb.append(",\n  \"")
                                        .append(k)
                                        .append("\" : ")
                                        .append(v));
                                Files.writeString(
                                        cfg.resolve("settings.json"),
                                        sb.append("\n}\n").toString());
                            }
                            fx = FxWindowFixture.create(cfg, zen, expert, simple, List.of(), true, c -> {});
                            fixtures.add(fx);
                            int fw = w, fh = h;
                            ctl = fx.controller;
                            FxTestSupport.runOnFx(() -> {
                                stage = FxTestSupport.field(ctl, "stage");
                                stage.setWidth(fw);
                                stage.setHeight(fh);
                                if (!stage.isShowing()) stage.show();
                            });
                            FxTestSupport.awaitFxCondition(() -> stage.isShowing(), "the window to show");
                            settle(1500);
                            if (seed.containsKey("theme")) {
                                String th = seed.get("theme").replace("\"", "");
                                FxTestSupport.runOnFx(() -> javafx.application.Application.setUserAgentStylesheet(
                                        Themes.stylesheetFor(th)));
                                settle(800);
                            }
                        }
                        case "project" -> {
                            int before = FxTestSupport.callOnFx(
                                    () -> FxTestSupport.<List<?>>field(fx.windowManager, "windows")
                                            .size());
                            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                                    ctl, "openProjectRoot", new Class<?>[] {Path.class}, Path.of(arg)));
                            // The project opens in a window of its own, or in this one when it was empty.
                            PulseChecks.holdsOnFxWithin(
                                    () -> FxTestSupport.<List<?>>field(fx.windowManager, "windows")
                                                    .size()
                                            > before,
                                    java.time.Duration.ofSeconds(10));
                            settle(2500);
                            int fw = w, fh = h;
                            FxTestSupport.runOnFx(() -> {
                                List<?> holders = FxTestSupport.<List<?>>field(fx.windowManager, "windows");
                                Object last = holders.get(holders.size() - 1);
                                Stage st = (Stage) FxTestSupport.call(last, "stage", new Class<?>[] {});
                                if (st != stage) {
                                    stage.hide();
                                    stage = st;
                                    ctl = (MainController) FxTestSupport.call(last, "controller", new Class<?>[] {});
                                }
                                stage.setX(0);
                                stage.setY(0);
                                stage.setWidth(fw);
                                stage.setHeight(fh);
                                stage.requestFocus();
                            });
                            settle(1200);
                        }
                        case "open" -> {
                            String[] s = arg.split("\\s+");
                            int ln = s.length > 1 ? Integer.parseInt(s[1]) - 1 : 0;
                            FxTestSupport.runOnFx(() -> ctl.openAndNavigate(Path.of(s[0]), ln));
                            settle(1500);
                        }
                        case "run" -> {
                            // Not runOnFx: a command that opens a modal dialog would block it forever.
                            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
                            javafx.application.Platform.runLater(() -> {
                                started.countDown();
                                com.editora.command.CommandRegistry reg = FxTestSupport.field(ctl, "registry");
                                boolean ok = reg.run(arg);
                                if (!ok) System.out.println("  !! command not run: " + arg);
                            });
                            if (!started.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IllegalStateException("the FX thread did not take the command");
                            }
                            settle(900);
                        }
                        case "wait" -> settle(Integer.parseInt(arg));
                        case "waitfor" -> { // waitfor <css>: until a visible node matches in any showing window (60 s)
                            String css = arg.replace('+', ' ');
                            FxTestSupport.awaitFxCondition(
                                    () -> {
                                        for (Window win : Window.getWindows()) {
                                            if (!win.isShowing() || win.getScene() == null) continue;
                                            for (Node n :
                                                    win.getScene().getRoot().lookupAll(css)) {
                                                if (n.isVisible()) return true;
                                            }
                                        }
                                        return false;
                                    },
                                    css,
                                    java.time.Duration.ofSeconds(60));
                            settle(300);
                        }
                        case "type" -> {
                            for (char ch : arg.replace("\\n", "\n").toCharArray()) {
                                String s = String.valueOf(ch);
                                fire(new KeyEvent(
                                        KeyEvent.KEY_TYPED, s, s, KeyCode.UNDEFINED, false, false, false, false));
                                PulseChecks.pulses(1); // one key a frame, as a typist's keys reach the scene
                            }
                            settle(500);
                        }
                        case "key" -> {
                            String[] s = arg.split("\\s+");
                            KeyCode code = KeyCode.valueOf(s[0]);
                            String m = s.length > 1 ? s[1] : "";
                            int n = s.length > 2 ? Integer.parseInt(s[2]) : 1;
                            for (int i = 0; i < n; i++) {
                                fire(new KeyEvent(
                                        KeyEvent.KEY_PRESSED,
                                        "",
                                        "",
                                        code,
                                        m.contains("S"),
                                        m.contains("C"),
                                        m.contains("M"),
                                        false));
                                fire(new KeyEvent(
                                        KeyEvent.KEY_RELEASED,
                                        "",
                                        "",
                                        code,
                                        m.contains("S"),
                                        m.contains("C"),
                                        m.contains("M"),
                                        false));
                            }
                            settle(400);
                        }
                        case "windows" ->
                            FxTestSupport.runOnFx(() -> {
                                for (Window win : Window.getWindows()) {
                                    System.out.println(
                                            "  window " + win.getClass().getSimpleName() + " showing=" + win.isShowing()
                                                    + " " + win.getX() + "," + win.getY() + " " + win.getWidth() + "x"
                                                    + win.getHeight());
                                }
                            });
                        case "windump" ->
                            FxTestSupport.runOnFx(() -> {
                                Window top = topExtra();
                                if (top != null) dump(top.getScene().getRoot(), 0, Integer.parseInt(arg));
                            });
                        case "clicksel" -> {
                            String[] z = arg.split("\\s+");
                            int nth = z.length > 1 ? Integer.parseInt(z[1]) : 0;
                            boolean main = z.length > 2 && z[2].equals("main");
                            FxTestSupport.runOnFx(() -> {
                                Window top = main ? stage : topExtra();
                                if (top == null) top = stage;
                                List<Node> hits = new ArrayList<>();
                                for (Node n : top.getScene().getRoot().lookupAll(z[0].replace('+', ' '))) {
                                    if (n.isVisible() && n.getScene() != null) hits.add(n);
                                }
                                System.out.println("  clicksel " + z[0] + " hits=" + hits.size());
                                if (nth >= hits.size()) return;
                                Node n = hits.get(nth);
                                if (n instanceof javafx.scene.control.ButtonBase b) {
                                    b.fire();
                                    return;
                                }
                                var b = n.getBoundsInLocal();
                                double x = b.getCenterX(), y = b.getCenterY();
                                var sp = n.localToScene(x, y);
                                var scr = n.localToScreen(x, y);
                                for (var type : List.of(
                                        javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                                        javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                                        javafx.scene.input.MouseEvent.MOUSE_CLICKED)) {
                                    Event.fireEvent(
                                            n,
                                            new javafx.scene.input.MouseEvent(
                                                    type,
                                                    x,
                                                    y,
                                                    scr == null ? sp.getX() : scr.getX(),
                                                    scr == null ? sp.getY() : scr.getY(),
                                                    javafx.scene.input.MouseButton.PRIMARY,
                                                    1,
                                                    false,
                                                    false,
                                                    false,
                                                    false,
                                                    type != javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                                                    false,
                                                    false,
                                                    true,
                                                    false,
                                                    true,
                                                    null));
                                }
                            });
                            settle(1200);
                        }
                        case "clickat" -> {
                            String[] z = arg.split("\\s+");
                            double px = Double.parseDouble(z[0]), py = Double.parseDouble(z[1]);
                            int count = z.length > 2 ? Integer.parseInt(z[2]) : 1;
                            boolean main = z.length > 3 && z[3].equals("main");
                            FxTestSupport.runOnFx(() -> {
                                Window top = main ? stage : topExtra();
                                if (top == null) top = stage;
                                Node hit = null;
                                for (Node n : top.getScene().getRoot().lookupAll("*")) {
                                    if (!n.isVisible() || n.isMouseTransparent() || n.getScene() == null) continue;
                                    if (n.localToScene(n.getBoundsInLocal()).contains(px, py)) hit = n;
                                }
                                System.out.println("  clickat -> " + hit);
                                if (hit == null) return;
                                var lp = hit.sceneToLocal(px, py);
                                var scr = hit.localToScreen(lp.getX(), lp.getY());
                                for (int c = 1; c <= count; c++) {
                                    for (var type : List.of(
                                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                                            javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                                            javafx.scene.input.MouseEvent.MOUSE_CLICKED)) {
                                        Event.fireEvent(
                                                hit,
                                                new javafx.scene.input.MouseEvent(
                                                        type,
                                                        lp.getX(),
                                                        lp.getY(),
                                                        scr == null ? px : scr.getX(),
                                                        scr == null ? py : scr.getY(),
                                                        javafx.scene.input.MouseButton.PRIMARY,
                                                        c,
                                                        false,
                                                        false,
                                                        false,
                                                        false,
                                                        type == javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                                                        false,
                                                        false,
                                                        true,
                                                        false,
                                                        true,
                                                        null));
                                    }
                                }
                            });
                            settle(1200);
                        }
                        case "focussel" -> {
                            FxTestSupport.runOnFx(() -> {
                                Window top = topExtra();
                                if (top == null) top = stage;
                                keyWindow = top;
                                for (Node n : top.getScene().getRoot().lookupAll(arg.replace('+', ' '))) {
                                    if (n.isVisible()) {
                                        n.requestFocus();
                                        System.out.println("  focussel -> " + n + " owner="
                                                + top.getScene().getFocusOwner());
                                        break;
                                    }
                                }
                            });
                            settle(400);
                        }
                        case "settext" -> { // settext <css> <text…>: set a Labeled's text ("" with no text)
                            String sel = arg.split(" ", 2)[0];
                            String txt = arg.contains(" ") ? arg.split(" ", 2)[1] : "";
                            FxTestSupport.runOnFx(() -> {
                                for (javafx.stage.Window ww : javafx.stage.Window.getWindows()) {
                                    if (ww.getScene() == null) continue;
                                    for (Node n : ww.getScene().getRoot().lookupAll(sel)) {
                                        if (n instanceof javafx.scene.control.Labeled l) l.setText(txt);
                                    }
                                }
                            });
                            settle(300);
                        }
                        case "retext" -> { // retext <regex> <replacement…>: rewrite matching Labeled text in every
                            // window
                            String rx = arg.split(" => ", 2)[0];
                            String rep = arg.contains(" => ") ? arg.split(" => ", 2)[1] : "";
                            FxTestSupport.runOnFx(() -> {
                                for (javafx.stage.Window ww : javafx.stage.Window.getWindows()) {
                                    if (ww.getScene() == null) continue;
                                    for (Node n : ww.getScene().getRoot().lookupAll("*")) {
                                        if (n instanceof javafx.scene.control.Labeled l
                                                && l.getText() != null
                                                && java.util.regex.Pattern.compile(rx)
                                                        .matcher(l.getText())
                                                        .find())
                                            l.setText(l.getText().replaceAll(rx, rep));
                                        if (n instanceof javafx.scene.control.TextInputControl t
                                                && t.getText() != null
                                                && java.util.regex.Pattern.compile(rx)
                                                        .matcher(t.getText())
                                                        .find())
                                            t.setText(t.getText().replaceAll(rx, rep));
                                    }
                                }
                            });
                            settle(300);
                        }
                        case "keymain" -> keyWindow = null;
                        case "dividers" ->
                            FxTestSupport.runOnFx(() -> {
                                int i = 0;
                                for (Node n : stage.getScene().getRoot().lookupAll(".split-pane")) {
                                    if (n instanceof javafx.scene.control.SplitPane sp && n.isVisible()) {
                                        System.out.println("  split " + i++ + " " + sp.getOrientation() + " "
                                                + java.util.Arrays.toString(sp.getDividerPositions()) + " "
                                                + (int) sp.getWidth() + "x" + (int) sp.getHeight());
                                    }
                                }
                            });
                        case "divider" -> {
                            String[] z = arg.split("\\s+");
                            FxTestSupport.runOnFx(() -> {
                                int i = 0;
                                for (Node n : stage.getScene().getRoot().lookupAll(".split-pane")) {
                                    if (n instanceof javafx.scene.control.SplitPane sp && n.isVisible()) {
                                        if (i++ == Integer.parseInt(z[0]))
                                            sp.setDividerPosition(
                                                    z.length > 2 ? Integer.parseInt(z[2]) : 0,
                                                    Double.parseDouble(z[1]));
                                    }
                                }
                            });
                            settle(800);
                        }
                        case "fieldtext" -> { // fieldtext <prompt prefix>|<text>: set a text field's text in any window
                            String[] pt = arg.split("\\|", 2);
                            FxTestSupport.runOnFx(() -> {
                                for (Window win : Window.getWindows()) {
                                    if (!win.isShowing() || win.getScene() == null) continue;
                                    for (Node n : win.getScene().getRoot().lookupAll(".text-field")) {
                                        if (n instanceof javafx.scene.control.TextField tf
                                                && tf.getPromptText() != null
                                                && tf.getPromptText().startsWith(pt[0])) {
                                            tf.setText(pt[1]);
                                            System.out.println("  fieldtext -> " + tf.getPromptText());
                                        }
                                    }
                                }
                            });
                            settle(600);
                        }
                        case "installdebuglog" -> com.editora.ui.DebugLog.install();
                        case "ctxbp" -> { // ctxbp <n>: right-click the nth breakpoint glyph in the gutter (its menu
                            // opens)
                            int nth = arg.isBlank() ? 0 : Integer.parseInt(arg.trim());
                            FxTestSupport.runOnFx(() -> {
                                List<Node> hits = new ArrayList<>();
                                for (Node n : stage.getScene().getRoot().lookupAll(".breakpoint-marker")) {
                                    if (n.isVisible() && n.getScene() != null) hits.add(n);
                                }
                                System.out.println("  ctxbp hits=" + hits.size());
                                if (nth >= hits.size()) return;
                                Node slot = hits.get(nth);
                                while (slot != null && !slot.getStyleClass().contains("breakpoint-slot"))
                                    slot = slot.getParent();
                                if (slot == null) return;
                                var b = slot.getBoundsInLocal();
                                double x = b.getCenterX(), y = b.getMaxY() + 2; // the menu opens under the line
                                var sp = slot.localToScene(x, y);
                                var scr = slot.localToScreen(x, y);
                                Event.fireEvent(
                                        slot,
                                        new javafx.scene.input.ContextMenuEvent(
                                                javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                                                x,
                                                y,
                                                scr == null ? sp.getX() : scr.getX(),
                                                scr == null ? sp.getY() : scr.getY(),
                                                false,
                                                null));
                            });
                            settle(1200);
                        }
                        case "ctxrow" -> { // ctxrow <css>: right-click the selected cell under <css> (its context menu
                            // opens)
                            FxTestSupport.runOnFx(() -> {
                                Node cell = null;
                                for (Node n : stage.getScene().getRoot().lookupAll(arg.replace('+', ' '))) {
                                    for (Node c : n.lookupAll(".tree-cell")) {
                                        if (c.isVisible()
                                                && c.getPseudoClassStates().stream()
                                                        .anyMatch(pc -> pc.getPseudoClassName()
                                                                .equals("selected"))) cell = c;
                                    }
                                    if (cell != null) break;
                                }
                                System.out.println("  ctxrow -> " + cell);
                                if (cell == null) return;
                                var b = cell.getBoundsInLocal();
                                double x = b.getMinX() + 60, y = b.getCenterY();
                                var sp = cell.localToScene(x, y);
                                var scr = cell.localToScreen(x, y);
                                Event.fireEvent(
                                        cell,
                                        new javafx.scene.input.ContextMenuEvent(
                                                javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                                                x,
                                                y,
                                                scr == null ? sp.getX() : scr.getX(),
                                                scr == null ? sp.getY() : scr.getY(),
                                                false,
                                                null));
                            });
                            settle(1000);
                        }
                        case "popitem" -> { // popitem <text>: fire the item (submenus walked) of a showing ContextMenu
                            boolean[] hit = new boolean[1];
                            FxTestSupport.runOnFx(() -> {
                                for (Window pw : new ArrayList<Window>(Window.getWindows())) {
                                    if (pw instanceof javafx.scene.control.ContextMenu cm
                                            && cm.isShowing()
                                            && !hit[0]) {
                                        MenuItemHit.find(cm.getItems(), arg, hit);
                                        cm.hide();
                                    }
                                }
                            });
                            if (!hit[0]) System.out.println("  !! no popup item: " + arg);
                            settle(1200);
                        }
                        case "combotext" -> { // combotext <text>: type into the visible editable ComboBox of any window
                            FxTestSupport.runOnFx(() -> {
                                for (Window cw : new ArrayList<Window>(Window.getWindows())) {
                                    if (!cw.isShowing() || cw.getScene() == null) continue;
                                    for (Node n : cw.getScene().getRoot().lookupAll(".combo-box-base")) {
                                        if (n instanceof javafx.scene.control.ComboBox<?> cb
                                                && cb.isEditable()
                                                && cb.isVisible()) {
                                            cb.getEditor().setText(arg);
                                            System.out.println("  combotext -> " + arg);
                                        }
                                    }
                                }
                            });
                            settle(400);
                        }
                        case "shot" -> shot(arg);
                        case "dump" ->
                            FxTestSupport.runOnFx(() -> dump(stage.getScene().getRoot(), 0, Integer.parseInt(arg)));
                        case "menuitem" -> {
                            boolean[] hit = new boolean[1];
                            FxTestSupport.runOnFx(() -> {
                                Window top = topExtra();
                                if (top == null) top = stage;
                                for (Node n : top.getScene().getRoot().lookupAll("*")) {
                                    if (hit[0] || !(n instanceof javafx.scene.control.MenuButton mb)) continue;
                                    MenuItemHit.find(mb.getItems(), arg, hit);
                                }
                            });
                            if (!hit[0]) System.out.println("  !! no menu item: " + arg);
                            settle(1200);
                        }
                        case "maplinks" -> {
                            String[] z = arg.split("\\s+");
                            FxTestSupport.runOnFx(() -> {
                                ProjectMapView view = mapView();
                                ProjectMapPreview card = null;
                                for (ProjectMapPreview c : view.cards.previews.values()) card = c;
                                System.out.println("  maplinks from " + (card == null ? null : card.path()));
                                view.cards.showLinks(
                                        card,
                                        MapCardLinks.Kind.valueOf(z[0]),
                                        Integer.parseInt(z[1]),
                                        Integer.parseInt(z[2]));
                            });
                            settle(2500);
                        }
                        case "maphover" -> {
                            FxTestSupport.runOnFx(() -> {
                                ProjectMapView view = mapView();
                                MapSurface surface = null;
                                for (Node n : view.lookupAll("*")) if (n instanceof MapSurface m) surface = m;
                                List<MapSurface.NodeBox> boxes = FxTestSupport.field(surface, "boxes");
                                MapSurface.NodeBox box = null;
                                for (MapSurface.NodeBox b : boxes) {
                                    if (b.entry()
                                            .path()
                                            .toString()
                                            .replace('\\', '/')
                                            .endsWith(arg)) box = b;
                                }
                                System.out.println("  maphover " + arg + " -> " + box);
                                if (box == null) return;
                                double x = box.x() + Math.min(box.width() / 2, 60), y = box.y() + box.height() / 2;
                                var scr = surface.localToScreen(x, y);
                                var sp = surface.localToScene(x, y);
                                for (var type : List.of(
                                        javafx.scene.input.MouseEvent.MOUSE_ENTERED,
                                        javafx.scene.input.MouseEvent.MOUSE_MOVED,
                                        javafx.scene.input.MouseEvent.MOUSE_MOVED)) {
                                    Event.fireEvent(
                                            surface,
                                            new javafx.scene.input.MouseEvent(
                                                    type,
                                                    x,
                                                    y,
                                                    scr == null ? sp.getX() : scr.getX(),
                                                    scr == null ? sp.getY() : scr.getY(),
                                                    javafx.scene.input.MouseButton.NONE,
                                                    0,
                                                    false,
                                                    false,
                                                    false,
                                                    false,
                                                    false,
                                                    false,
                                                    false,
                                                    true,
                                                    false,
                                                    true,
                                                    null));
                                }
                            });
                            settle(1500);
                        }
                        case "winshotp" -> winshotp(arg);
                        default -> System.out.println("  ?? " + line);
                    }
                    System.out.println("ok " + line);
                } catch (Throwable t) {
                    System.out.println("FAIL line " + step.line() + ": " + line + " -> " + t);
                    failures++;
                }
            }
        } finally {
            for (FxWindowFixture f : fixtures) {
                f.dispose();
            }
            fixtures.clear();
        }
        return failures;
    }

    private Window topExtra() {
        Window top = null;
        for (Window win : Window.getWindows()) {
            if (win != stage && win.isShowing() && win instanceof Stage && win.getScene() != null) top = win;
        }
        return top;
    }

    private void fire(KeyEvent ev) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Window target = stage;
            for (Window win : Window.getWindows()) {
                if (win.isShowing() && win.isFocused() && win != stage) target = win;
            }
            if (keyWindow != null && keyWindow.isShowing()) target = keyWindow;
            Scene sc = target.getScene();
            Node owner = sc.getFocusOwner();
            Event.fireEvent(owner != null ? owner : sc.getRoot(), ev);
        });
    }

    /** Canvases (the Project Map, the terminal) rasterise at the window's render scale, so match the snapshot scale. */
    private void hidpi() throws Exception {
        aliasHome();
        FxTestSupport.runOnFx(() -> {
            for (Window win : Window.getWindows()) {
                if (win.getRenderScaleX() != scale) {
                    FxTestSupport.renderAtShotScale(win);
                    win.setWidth(win.getWidth() + 1); // a relayout redraws the canvases at the new scale
                }
            }
        });
        settle(400);
        FxTestSupport.runOnFx(() -> {
            for (Window win : Window.getWindows()) {
                if (win.getWidth() % 2 == 1) win.setWidth(win.getWidth() - 1);
            }
        });
        settle(600);
    }

    /**
     * Lets about {@code ms} of FX pulses go by (60 a second), then drains the FX queue: layout, CSS and Editora's
     * debounced work (pause transitions and timelines) all advance on pulses, so this waits on what the UI itself
     * counts, not on a sleep. It counts in chunks of 300 pulses (5 s), because one {@code PulseChecks.pulses} call
     * gives up after 10 s: a {@code wait 60000} for a language server would otherwise fail after 10.
     */
    private void settle(int ms) throws Exception {
        for (int left = Math.max(2, ms * 60 / 1000); left > 0; left -= 300) {
            PulseChecks.pulses(Math.min(300, left));
        }
        FxTestSupport.drainFx();
    }

    private void dump(Node n, int depth, int max) {
        if (depth > max || !n.isVisible()) return;
        var b = n.localToScene(n.getBoundsInLocal());
        String id = n.getId() == null ? "" : "#" + n.getId();
        System.out.println("  " + "  ".repeat(depth) + n.getClass().getSimpleName() + id + " " + n.getStyleClass() + " "
                + (int) b.getMinX() + "," + (int) b.getMinY() + " " + (int) b.getWidth() + "x" + (int) b.getHeight());
        if (n instanceof javafx.scene.Parent par) {
            for (Node c : par.getChildrenUnmodifiable()) dump(c, depth + 1, max);
        }
    }

    /** shot FILE [x y w h] — logical scene coordinates; popups and secondary windows are composited in. */
    private void shot(String arg) throws Exception {
        String[] s = arg.split("\\s+");
        hidpi();
        settle(300);
        List<Object[]> layers = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            Scene main = stage.getScene();
            layers.add(new Object[] {snap(main), 0.0, 0.0});
            double ox = stage.getX() + main.getX(), oy = stage.getY() + main.getY();
            for (Window win : Window.getWindows()) {
                if (win == stage || !win.isShowing() || win.getScene() == null) continue;
                Scene sc = win.getScene();
                layers.add(new Object[] {snap(sc), win.getX() + sc.getX() - ox, win.getY() + sc.getY() - oy});
            }
        });
        WritableImage base = (WritableImage) layers.get(0)[0];
        int W = (int) base.getWidth(), H = (int) base.getHeight();
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        for (Object[] l : layers) {
            WritableImage img = (WritableImage) l[0];
            g.drawImage(
                    toBuffered(img),
                    (int) Math.round((double) l[1] * scale),
                    (int) Math.round((double) l[2] * scale),
                    null);
        }
        g.dispose();
        int[] sceneSize = new int[2];
        FxTestSupport.runOnFx(() -> {
            sceneSize[0] = (int) (stage.getScene().getWidth() * scale);
            sceneSize[1] = (int) (stage.getScene().getHeight() * scale);
        });
        if (s.length < 5 && (W > sceneSize[0] || H > sceneSize[1])) {
            out = out.getSubimage(0, 0, Math.min(W, sceneSize[0]), Math.min(H, sceneSize[1]));
        }
        if (s.length >= 5) {
            int x = (int) (Double.parseDouble(s[1]) * scale), y = (int) (Double.parseDouble(s[2]) * scale);
            int cw = (int) (Double.parseDouble(s[3]) * scale), ch = (int) (Double.parseDouble(s[4]) * scale);
            cw = Math.min(cw, W - x);
            ch = Math.min(ch, H - y);
            out = out.getSubimage(x, y, cw, ch);
        }
        Path file = Path.of(s[0]);
        Files.createDirectories(file.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(out, "png", file.toFile());
        System.out.println(
                "  wrote " + file + " " + out.getWidth() + "x" + out.getHeight() + " layers=" + layers.size());
        written.add(new Written(file, out.getWidth(), out.getHeight(), sceneSize[0] / scale, sceneSize[1] / scale));
    }

    /** winshot FILE — the topmost extra Stage (a dialog or the Settings window) on its own. */
    private void winshot(String arg) throws Exception {
        FxTestSupport.awaitFxCondition(
                () -> topExtra() != null, "a dialog or second window", java.time.Duration.ofSeconds(10));
        hidpi();
        settle(300);
        WritableImage[] img = new WritableImage[1];
        double[] size = new double[2];
        FxTestSupport.runOnFx(() -> {
            for (Window win : Window.getWindows()) {
                if (win != stage && win.isShowing() && win instanceof Stage && win.getScene() != null) {
                    img[0] = snap(win.getScene());
                    size[0] = win.getScene().getWidth();
                    size[1] = win.getScene().getHeight();
                }
            }
        });
        if (img[0] == null) throw new IllegalStateException("no extra window");
        Path file = Path.of(arg);
        Files.createDirectories(file.toAbsolutePath().getParent());
        BufferedImage b = toBuffered(img[0]);
        javax.imageio.ImageIO.write(b, "png", file.toFile());
        System.out.println("  wrote " + file + " " + b.getWidth() + "x" + b.getHeight());
        written.add(new Written(file, b.getWidth(), b.getHeight(), size[0], size[1]));
    }

    private WritableImage snap(Scene sc) {
        return FxTestSupport.snapshotAtShotScale(sc);
    }

    private BufferedImage toBuffered(WritableImage img) {
        return FxTestSupport.toBufferedImage(img);
    }

    /** With a home alias set, the scratch home's path reads as the alias in every window's text. */
    private void aliasHome() throws Exception {
        String home = System.getProperty("user.home", "");
        if (HOME_ALIAS.isEmpty() || home.isEmpty() || home.equals(HOME_ALIAS)) return;
        FxTestSupport.runOnFx(() -> {
            for (Window ww : Window.getWindows()) {
                if (ww.getScene() == null) continue;
                for (Node n : ww.getScene().getRoot().lookupAll("*")) {
                    if (n instanceof javafx.scene.control.Labeled l
                            && l.getText() != null
                            && l.getText().contains(home)) {
                        l.setText(l.getText().replace(home, HOME_ALIAS));
                    }
                    if (n instanceof javafx.scene.control.TextInputControl t
                            && t.getText() != null
                            && t.getText().contains(home)) {
                        t.setText(t.getText().replace(home, HOME_ALIAS));
                    }
                }
            }
        });
    }

    private ProjectMapView mapView() {
        for (Window win : Window.getWindows()) {
            if (!win.isShowing() || win.getScene() == null) continue;
            for (Node n : win.getScene().getRoot().lookupAll("*")) if (n instanceof ProjectMapView v) return v;
        }
        throw new IllegalStateException("no Project Map");
    }

    static final class MenuItemHit {
        static void find(List<javafx.scene.control.MenuItem> items, String text, boolean[] hit) {
            for (javafx.scene.control.MenuItem item : List.copyOf(items)) {
                if (hit[0]) return;
                if (item instanceof javafx.scene.control.Menu m) {
                    if (m.getOnShowing() != null)
                        m.getOnShowing().handle(new Event(javafx.scene.control.Menu.ON_SHOWING));
                    find(m.getItems(), text, hit);
                } else if (item.getText() != null
                        && item.getText().replace("_", "").startsWith(text)
                        && !item.isDisable()) {
                    System.out.println("  menuitem -> " + item.getText());
                    if (item instanceof javafx.scene.control.CheckMenuItem c) c.setSelected(!c.isSelected());
                    item.fire();
                    hit[0] = true;
                }
            }
        }
    }

    /** winshotp FILE — the topmost extra Stage with its popups (tooltips, menus) composited in. */
    private void winshotp(String arg) throws Exception {
        hidpi();
        settle(300);
        List<Object[]> layers = new ArrayList<>();
        double[] size = new double[2];
        FxTestSupport.runOnFx(() -> {
            Window top = topExtra();
            if (top == null) top = stage;
            Scene main = top.getScene();
            layers.add(new Object[] {snap(main), 0.0, 0.0});
            size[0] = main.getWidth();
            size[1] = main.getHeight();
            double ox = top.getX() + main.getX(), oy = top.getY() + main.getY();
            for (Window win : Window.getWindows()) {
                if (!(win instanceof javafx.stage.PopupWindow) || !win.isShowing() || win.getScene() == null) continue;
                Scene sc = win.getScene();
                layers.add(new Object[] {snap(sc), win.getX() + sc.getX() - ox, win.getY() + sc.getY() - oy});
            }
        });
        WritableImage base = (WritableImage) layers.get(0)[0];
        BufferedImage out =
                new BufferedImage((int) base.getWidth(), (int) base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        for (Object[] l : layers) {
            g.drawImage(
                    toBuffered((WritableImage) l[0]),
                    (int) Math.round((double) l[1] * scale),
                    (int) Math.round((double) l[2] * scale),
                    null);
        }
        g.dispose();
        Path file = Path.of(arg);
        Files.createDirectories(file.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(out, "png", file.toFile());
        System.out.println(
                "  wrote " + file + " " + out.getWidth() + "x" + out.getHeight() + " layers=" + layers.size());
        written.add(new Written(file, out.getWidth(), out.getHeight(), size[0], size[1]));
    }
}
