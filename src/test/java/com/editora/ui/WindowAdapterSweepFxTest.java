package com.editora.ui;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window hands each of its coordinators and panels an adapter onto itself — some forty of them, each a
 * handful of one-line methods. A method wired to the wrong field (another coordinator's, or none) fails only
 * in the one feature that calls it. This goes through every adapter a window holds and checks the methods
 * they have in common: each must reach <em>this</em> window's service, status bar, caret and tabs.
 *
 * <p>The adapters are found by walking the window's fields, so a new coordinator's adapter is checked
 * without touching this test.
 */
@Tag("fx")
class WindowAdapterSweepFxTest {

    @TempDir
    Path dir;

    private FxWindowFixture fx;
    private MainController controller;
    private Map<Object, String> adapters;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        fx = FxWindowFixture.create();
        controller = fx.controller;
        adapters = FxTestSupport.callOnFx(() -> adapters(controller));
        assertTrue(adapters.size() >= 35, "the window's adapters were found: " + adapters.size());
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.dispose();
    }

    /** Every adapter object reachable from the window: its inner and anonymous classes, with where it was found. */
    static Map<Object, String> adapters(MainController controller) {
        Map<Object, String> found = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[] {controller, "window", 0});
        seen.add(controller);
        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            Object target = item[0];
            int depth = (Integer) item[2];
            for (Class<?> c = target.getClass();
                    c != null && c.getName().startsWith("com.editora.");
                    c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                        continue;
                    }
                    Object value;
                    try {
                        f.setAccessible(true);
                        value = f.get(target);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        continue;
                    }
                    if (value == null || !seen.add(value)) {
                        continue;
                    }
                    String name = value.getClass().getName();
                    String path = item[1] + "." + f.getName();
                    if (name.startsWith("com.editora.ui.MainController$") && !name.contains("$$Lambda")) {
                        found.put(value, path);
                    }
                    if (depth < 2 && name.startsWith("com.editora.ui.") && !(value instanceof javafx.scene.Node)) {
                        queue.add(new Object[] {value, path, depth + 1});
                    }
                }
            }
        }
        return found;
    }

    /** The one adapter of {@code controller} that implements {@code type}. To be called on the FX thread. */
    static <T> T adapter(MainController controller, Class<T> type) {
        List<T> matches = adapters(controller).keySet().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(matches.size() + " adapters implement " + type.getName());
        }
        return matches.get(0);
    }

    private record Call(Object adapter, Method method, String where) {
        Object invoke(Object... args) {
            try {
                method.setAccessible(true);
                return method.invoke(adapter, args);
            } catch (InvocationTargetException e) {
                throw new AssertionError(where + " threw", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(where, e);
            }
        }
    }

    /** The adapters' own methods called {@code name} that take exactly {@code parameters}. */
    private List<Call> calls(String name, Class<?>... parameters) {
        List<Call> out = new ArrayList<>();
        adapters.forEach((adapter, path) -> {
            for (Method m : adapter.getClass().getDeclaredMethods()) {
                if (!m.isSynthetic()
                        && !Modifier.isStatic(m.getModifiers())
                        && m.getName().equals(name)
                        && List.of(m.getParameterTypes()).equals(List.of(parameters))) {
                    out.add(new Call(adapter, m, path + "." + name));
                }
            }
        });
        return out;
    }

    private String echo() {
        StatusBar status = FxTestSupport.field(controller, "statusBar");
        return FxTestSupport.<Label>field(status, "echo").getText();
    }

    private EditorBuffer active() {
        return (EditorBuffer) FxTestSupport.call(controller, "activeBuffer", new Class<?>[] {});
    }

    private EditorBuffer addBuffer(String content) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setContent(content);
        FxTestSupport.call(controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
        return buffer;
    }

    /**
     * An adapter method with no arguments, named like one of the window's fields and returning that field's
     * type, hands out that field — this window's stage, registry, coordinator — and nothing else.
     */
    @Test
    void anAdapterMethodNamedAfterAWindowFieldReturnsThatField() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Map<String, Field> fields = new LinkedHashMap<>();
            for (Field f : MainController.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    fields.put(f.getName(), f);
                }
            }
            int checked = 0;
            for (Map.Entry<Object, String> adapter : adapters.entrySet()) {
                for (Method m : adapter.getKey().getClass().getDeclaredMethods()) {
                    Field field = fields.get(m.getName());
                    if (m.isSynthetic()
                            || Modifier.isStatic(m.getModifiers())
                            || m.getParameterCount() != 0
                            || field == null
                            || field.getType().isPrimitive()
                            || !m.getReturnType().isAssignableFrom(field.getType())) {
                        continue;
                    }
                    Object expected;
                    try {
                        expected = field.get(controller);
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                    Object actual = new Call(adapter.getKey(), m, adapter.getValue() + "." + m.getName()).invoke();
                    assertSame(expected, actual, adapter.getValue() + "." + m.getName() + "()");
                    checked++;
                }
            }
            assertTrue(checked >= 150, "the getters were found: " + checked);
        });
    }

    @Test
    void everyAdapterThatCanOpenAFileOpensItInThisWindow() throws Exception {
        List<Call> opens = new ArrayList<>();
        for (String name : List.of("openPath", "openFile", "open")) {
            opens.addAll(calls(name, Path.class));
        }
        assertTrue(opens.size() >= 10, "the open methods were found: " + opens.size());
        int n = 0;
        for (Call open : opens) {
            Path file = Files.writeString(dir.resolve("opened-" + (n++) + ".txt"), open.where() + "\n");
            FxTestSupport.runOnFx(() -> open.invoke(file));
            SettingsRig.awaitFx(open.where() + " to open its file", () -> controller.hasFileOpen(file));
        }
    }

    @Test
    void everyAdapterThatReportsSomethingWritesItToThisWindowsStatusBar() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<Call> statuses = calls("setStatus", String.class);
            List<Call> errors = calls("setError", String.class);
            assertTrue(statuses.size() >= 10, "the status methods were found: " + statuses.size());
            assertTrue(errors.size() >= 3, "the error methods were found: " + errors.size());
            for (Call status : statuses) {
                String message = "said by " + status.where();
                status.invoke(message);
                assertEquals(message, echo());
            }
            for (Call error : errors) {
                String message = "failed in " + error.where();
                error.invoke(message);
                assertEquals(message, echo());
            }
        });
    }

    @Test
    void everyAdapterThatNavigatesMovesTheCaretOfTheActiveBuffer() throws Exception {
        FxTestSupport.runOnFx(() -> {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                text.append("line ").append(i).append('\n');
            }
            EditorBuffer buffer = addBuffer(text.toString());
            List<Call> navigations = calls("navigateToLine", int.class);
            assertTrue(navigations.size() >= 4, "the navigation methods were found: " + navigations.size());
            int line = 3;
            for (Call navigate : navigations) {
                navigate.invoke(line);
                assertEquals(line, buffer.getArea().getCurrentParagraph(), navigate.where());
                line += 4;
            }
        });
    }

    @Test
    void everyAdapterSeesTheSameActiveBufferAndAddsItsBuffersAsTabsOfThisWindow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer first = addBuffer("first\n");
            List<Call> actives = calls("activeBuffer");
            assertTrue(actives.size() >= 10, "the active-buffer methods were found: " + actives.size());
            for (Call active : actives) {
                assertSame(first, active.invoke(), active.where());
            }

            EditorArea area = FxTestSupport.field(controller, "editorArea");
            List<Call> adds = new ArrayList<>(calls("addBuffer", EditorBuffer.class));
            assertTrue(adds.size() >= 4, "the add-buffer methods were found: " + adds.size());
            for (Call add : adds) {
                EditorBuffer added = new EditorBuffer();
                Tab tab = (Tab) add.invoke(added);
                assertNotNull(tab, add.where());
                assertSame(added, tab.getUserData(), add.where());
                assertTrue(area.contains(tab), add.where());
                assertSame(added, active(), add.where() + " selects what it adds");
            }
            for (Call add : calls("addBuffer", EditorBuffer.class, boolean.class)) {
                EditorBuffer before = active();
                EditorBuffer background = new EditorBuffer();
                Tab tab = (Tab) add.invoke(background, false);
                assertTrue(area.contains(tab), add.where());
                assertSame(before, active(), add.where() + " leaves the selection alone when asked to");
                EditorBuffer foreground = new EditorBuffer();
                add.invoke(foreground, true);
                assertSame(foreground, active(), add.where());
            }
            for (Call add : calls("addBuffer", EditorBuffer.class, boolean.class, boolean.class)) {
                EditorBuffer added = new EditorBuffer();
                Tab tab = (Tab) add.invoke(added, true, false);
                assertSame(added, tab.getUserData(), add.where());
                assertSame(added, active(), add.where());
            }
        });
    }

    /** A yes/no question an adapter passes on is answered as the window itself answers it. */
    @Test
    void anAdapterAnswersAQuestionAboutTheWindowAsTheWindowDoes() throws Exception {
        FxTestSupport.runOnFx(() -> {
            addBuffer("text\n");
            int checked = 0;
            for (String question : List.of(
                    "projectsEnabled",
                    "lspEnabled",
                    "localHistoryEnabled",
                    "activeArea",
                    "windowProjectRoot",
                    "activeProjectRoot",
                    "invertBindings",
                    "selPolicy")) {
                Method own;
                try {
                    own = MainController.class.getDeclaredMethod(question);
                } catch (NoSuchMethodException e) {
                    continue;
                }
                Object expected = new Call(controller, own, "window." + question).invoke();
                for (Call call : calls(question)) {
                    assertEquals(expected, call.invoke(), call.where());
                    checked++;
                }
            }
            assertTrue(checked >= 10, "the questions were found: " + checked);
        });
    }
}
