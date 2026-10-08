package com.editora.editor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.editora.i18n.Messages;
import com.editora.markdown.MarkdownLint;
import com.editora.mermaid.MaidOutput;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pointer- and menu-driven parts of a buffer: the right-click menu and what its items do, the gutter Run
 * glyph's dispatch, Ctrl-click, the hover tooltips, the install banner, and paging a read-only view.
 */
@Tag("fx")
class EditorInteractionFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static EditorBuffer buffer(String language, String text) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride(language);
        buffer.getArea().replaceText(text);
        buffer.getArea().moveTo(0);
        return buffer;
    }

    private static String tr(String key, Object... args) {
        return Messages.tr(key, args);
    }

    /** Right-clicks the character at {@code offset} and returns the menu that opened. */
    private static ContextMenu rightClick(EditorBuffer buffer, int offset) {
        CodeArea area = buffer.getArea();
        buffer.getNode().applyCss();
        buffer.getNode().layout();
        Bounds screen = area.getCharacterBoundsOnScreen(offset, offset + 1)
                .orElseThrow(() -> new AssertionError("character " + offset + " is not on screen"));
        Point2D local = area.screenToLocal(screen.getCenterX(), screen.getCenterY());
        area.fireEvent(new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                local.getX(),
                local.getY(),
                screen.getCenterX(),
                screen.getCenterY(),
                false,
                null));
        ContextMenu menu = EditorFx.field(buffer, "contextMenu");
        assertTrue(menu.isShowing(), "the editor menu opened");
        return menu;
    }

    private static MouseEvent mouseAt(EditorBuffer buffer, int offset, boolean pressed, boolean shortcut) {
        CodeArea area = buffer.getArea();
        Bounds screen = area.getCharacterBoundsOnScreen(offset, offset + 1)
                .orElseThrow(() -> new AssertionError("character " + offset + " is not on screen"));
        Point2D local = area.screenToLocal(screen.getCenterX(), screen.getCenterY());
        return EditorFx.mouse(
                pressed ? MouseEvent.MOUSE_PRESSED : MouseEvent.MOUSE_MOVED,
                local.getX(),
                local.getY(),
                screen.getCenterX(),
                screen.getCenterY(),
                shortcut,
                pressed ? 1 : 0);
    }

    private static final String TEST_CLASS = """
            package demo;

            import org.junit.jupiter.api.Test;

            class OrderTest {

                @Test
                void totalsAreSummed() {
                }

                @Test
                void emptyOrderIsFree() {
                }
            }
            """;

    @Test
    void aTestFileRunsTheMethodOrClassUnderTheCaretFromTheGutterAndTheMenu() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("java", TEST_CLASS);
            List<String> ran = new ArrayList<>();
            assertNull(buffer.testClassTarget(), "the test gutter is off");
            assertNull(buffer.testTargetAtCaret(false));
            buffer.setTestRunHandler(t -> ran.add("run:" + t.className() + "#" + t.methodName()));
            buffer.setTestDebugHandler(t -> ran.add("debug:" + t.className() + "#" + t.methodName()));
            buffer.setTestGutterEnabled(true);
            buffer.setTestGutterEnabled(true);

            var classTarget = buffer.testClassTarget();
            assertNotNull(classTarget);
            assertNull(classTarget.methodName());
            assertTrue(classTarget.className().endsWith("OrderTest"), classTarget.className());

            CodeArea area = buffer.getArea();
            int second = TEST_CLASS.indexOf("emptyOrderIsFree");
            area.moveTo(second);
            assertEquals("emptyOrderIsFree", buffer.testTargetAtCaret(false).methodName());
            assertNull(buffer.testTargetAtCaret(true).methodName(), "class level drops the method");
            area.moveTo(0);
            assertNull(buffer.testTargetAtCaret(false), "above the class there is nothing to run");

            int methodLine = area.offsetToPosition(second, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                    .getMajor();
            EditorFx.call(buffer, "onRunGlyph", new Class<?>[] {int.class}, methodLine);
            assertEquals(List.of("run:" + classTarget.className() + "#emptyOrderIsFree"), ran);
            assertEquals(
                    tr("testrunner.gutter.runMethod", "emptyOrderIsFree"),
                    EditorFx.<String>call(buffer, "runGlyphTooltip", new Class<?>[] {int.class}, methodLine));
            assertTrue(EditorFx.<Boolean>call(buffer, "isRunGlyphLine", new Class<?>[] {int.class}, methodLine));
            assertFalse(EditorFx.<Boolean>call(buffer, "isRunGlyphLine", new Class<?>[] {int.class}, 0));
            assertNull(EditorFx.<String>call(buffer, "runGlyphTooltip", new Class<?>[] {int.class}, 0));

            Stage stage = EditorFx.show(buffer, 700, 420);
            ContextMenu menu = rightClick(buffer, second);
            EditorFx.menuItem(menu.getItems(), tr("editmenu.runTestMethod")).fire();
            EditorFx.menuItem(menu.getItems(), tr("testrunner.menu.debugTest")).fire();
            assertEquals(
                    List.of(
                            "run:" + classTarget.className() + "#emptyOrderIsFree",
                            "run:" + classTarget.className() + "#emptyOrderIsFree",
                            "debug:" + classTarget.className() + "#emptyOrderIsFree"),
                    ran);
            menu.hide();

            ContextMenu onClass = rightClick(buffer, TEST_CLASS.indexOf("OrderTest {"));
            assertNotNull(EditorFx.findMenuItem(onClass.getItems(), tr("editmenu.runTests")), "the class item");
            assertNotNull(EditorFx.findMenuItem(onClass.getItems(), tr("testrunner.menu.debugClass")));
            onClass.hide();

            buffer.setTestRunHandler(null);
            EditorFx.call(buffer, "onRunGlyph", new Class<?>[] {int.class}, methodLine);
            assertEquals(3, ran.size(), "with no handler the glyph does nothing");
            buffer.setTestGutterEnabled(false);
            assertNull(buffer.testClassTarget());
            stage.close();
            buffer.dispose();
        });
    }

    private static final String MAIN_CLASS = """
            package demo;

            public class Launcher {

                int unrelated;

                public static void main(String[] args) {
                }
            }
            """;

    @Test
    void aMainClassOffersRunAndDebugFromTheGutterAndTheMenu() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("java", MAIN_CLASS);
            List<String> ran = new ArrayList<>();
            assertNull(buffer.mainTargetAtCaret(), "the main gutter is off");
            buffer.setMainRunHandler(m -> ran.add("run:" + m.fqn()));
            buffer.setMainDebugHandler(m -> ran.add("debug:" + m.fqn()));
            buffer.setMainGutterEnabled(true);
            buffer.setMainGutterEnabled(true);

            var main = buffer.mainTargetAtCaret();
            assertNotNull(main, "caret above main(): the file's first entry point");
            assertEquals("demo.Launcher", main.fqn());
            buffer.getArea().moveTo(MAIN_CLASS.length());
            assertEquals(main, buffer.mainTargetAtCaret(), "caret below it: the nearest one above");

            EditorFx.call(buffer, "onRunGlyph", new Class<?>[] {int.class}, main.line());
            assertEquals(List.of("run:demo.Launcher"), ran);
            assertEquals(
                    tr("editmenu.runMainTooltip", "Launcher"),
                    EditorFx.<String>call(buffer, "runGlyphTooltip", new Class<?>[] {int.class}, main.line()));
            assertTrue(EditorFx.<Boolean>call(buffer, "isRunGlyphLine", new Class<?>[] {int.class}, main.line()));

            Stage stage = EditorFx.show(buffer, 700, 420);
            ContextMenu menu = rightClick(buffer, MAIN_CLASS.indexOf("unrelated"));
            EditorFx.menuItem(menu.getItems(), tr("editmenu.runMainClass", "Launcher"))
                    .fire();
            EditorFx.menuItem(menu.getItems(), tr("editmenu.debugMainClass", "Launcher"))
                    .fire();
            assertEquals(List.of("run:demo.Launcher", "run:demo.Launcher", "debug:demo.Launcher"), ran);
            menu.hide();

            buffer.setMainRunHandler(null);
            buffer.setMainDebugHandler(null);
            EditorFx.call(buffer, "onRunGlyph", new Class<?>[] {int.class}, main.line());
            assertEquals(3, ran.size());
            stage.close();
            buffer.dispose();
        });
    }

    @Test
    void theRunGlyphDispatchesByFileType() throws Exception {
        EditorFx.onFx(() -> {
            // An .http file: each request line runs that request.
            EditorBuffer http = buffer("http", "GET https://example.org/a\n\n###\nPOST https://example.org/b\n");
            List<Integer> requests = new ArrayList<>();
            AtomicInteger runnableChanges = new AtomicInteger();
            http.setOnRunnableChanged(runnableChanges::incrementAndGet);
            http.setHttpRunHandler(requests::add);
            assertTrue(http.isHttpFile());
            assertFalse(http.isRunnable());
            http.setHttpEnabled(true);
            http.setHttpEnabled(true);
            assertTrue(http.isRunnable());
            assertEquals(1, runnableChanges.get());
            assertTrue(EditorFx.<Boolean>call(http, "isRunGlyphLine", new Class<?>[] {int.class}, 0));
            assertFalse(EditorFx.<Boolean>call(http, "isRunGlyphLine", new Class<?>[] {int.class}, 1));
            EditorFx.call(http, "onRunGlyph", new Class<?>[] {int.class}, 3);
            assertEquals(List.of(3), requests);
            http.setHttpRunHandler(null);
            EditorFx.call(http, "onRunGlyph", new Class<?>[] {int.class}, 0);
            assertEquals(1, requests.size());
            http.setOnRunnableChanged(null);
            http.setHttpEnabled(false);
            assertFalse(http.isRunnable());
            assertEquals(1, runnableChanges.get());

            // A Makefile: a target line runs that target, any other line runs nothing.
            EditorBuffer make = buffer("makefile", "build:\n\tcc main.c\n\ntest: build\n\t./a.out\n");
            List<String> targets = new ArrayList<>();
            make.setMakeRunHandler(targets::add);
            make.setRunEnabled(false);
            assertFalse(make.isRunnable(), "the Run feature is off");
            make.setRunEnabled(true);
            assertTrue(make.isMakefile());
            assertTrue(make.isRunnable());
            java.util.Map<Integer, String> byLine = EditorFx.field(make, "makeTargets");
            assertEquals(java.util.Set.of("build", "test"), new java.util.HashSet<>(byLine.values()));
            int testLine = byLine.entrySet().stream()
                    .filter(e -> e.getValue().equals("test"))
                    .findFirst()
                    .orElseThrow()
                    .getKey();
            int recipeLine = testLine + 1;
            assertEquals("\t./a.out", make.lineText(recipeLine), "the line after the target is its recipe");
            assertEquals("", make.lineText(-1));
            assertEquals("", make.lineText(999));
            assertTrue(EditorFx.<Boolean>call(make, "isRunGlyphLine", new Class<?>[] {int.class}, testLine));
            assertFalse(EditorFx.<Boolean>call(make, "isRunGlyphLine", new Class<?>[] {int.class}, recipeLine));
            EditorFx.call(make, "onRunGlyph", new Class<?>[] {int.class}, testLine);
            EditorFx.call(make, "onRunGlyph", new Class<?>[] {int.class}, recipeLine);
            assertEquals(List.of("test"), targets);
            make.setMakeRunHandler(null);
            EditorFx.call(make, "onRunGlyph", new Class<?>[] {int.class}, testLine);
            assertEquals(1, targets.size());

            // A script: one entry glyph, the generic run handler.
            EditorBuffer python = buffer("python", "print('hi')\n");
            AtomicInteger runs = new AtomicInteger();
            assertTrue(python.isPython());
            assertFalse(python.isShell());
            assertFalse(python.isCompactSource());
            EditorFx.call(python, "onRunGlyph", new Class<?>[] {int.class}, 0); // nothing wired: no-op
            python.setRunHandler(runs::incrementAndGet);
            python.setRunEnabled(false);
            python.setRunEnabled(true);
            assertTrue(python.isRunnable());
            EditorFx.call(python, "onRunGlyph", new Class<?>[] {int.class}, 0);
            assertEquals(1, runs.get());

            Stage stage = EditorFx.show(python, 600, 300);
            ContextMenu menu = rightClick(python, 2);
            EditorFx.menuItem(menu.getItems(), tr("command.file.run")).fire();
            assertEquals(2, runs.get());
            menu.hide();
            python.setRunEnabled(false);
            ContextMenu off = rightClick(python, 2);
            assertNull(EditorFx.findMenuItem(off.getItems(), tr("command.file.run")), "Run goes with the feature");
            off.hide();
            stage.close();

            // A shell script's glyph is gated separately (by the Bash server toggle).
            EditorBuffer shell = buffer("shell", "#!/bin/sh\necho hi\n");
            shell.setRunEnabled(false);
            shell.setRunEnabled(true);
            assertTrue(shell.isShell());
            assertFalse(shell.isRunnable());
            shell.setShellRunEnabled(true);
            shell.setShellRunEnabled(true);
            assertTrue(shell.isRunnable());
            http.dispose();
            make.dispose();
            python.dispose();
            shell.dispose();
        });
    }

    @Test
    void theLanguageServerSubmenuActsOnTheRightClickedSymbol(@TempDir Path dir) throws Exception {
        EditorFx.onFx(() -> {
            String source = "class A {\n    void run() {}\n    void stop() {}\n}\n";
            EditorBuffer buffer = buffer("java", source);
            buffer.setPath(dir.resolve("A.java"));
            List<String> ran = new ArrayList<>();
            CodeArea area = buffer.getArea();
            buffer.setLspNavActions(
                    () -> ran.add("definition@" + area.getCaretPosition()),
                    () -> ran.add("references@" + area.getCaretPosition()),
                    () -> ran.add("hover@" + area.getCaretPosition()),
                    () -> ran.add("format"),
                    () -> ran.add("actions@" + area.getCaretPosition()),
                    () -> ran.add("rename@" + area.getCaretPosition()),
                    () -> ran.add("implementation@" + area.getCaretPosition()),
                    () -> ran.add("type@" + area.getCaretPosition()));
            buffer.setLspActive(true);
            assertTrue(buffer.isLspActive());
            Stage stage = EditorFx.show(buffer, 700, 420);

            int stop = source.indexOf("stop");
            ContextMenu menu = rightClick(buffer, stop);
            List<String> basic = EditorFx.titles(menu.getItems());
            assertTrue(basic.contains(tr("editmenu.lsp")), basic.toString());
            assertFalse(basic.contains(tr("command.lsp.rename")), "only what the server advertises is offered");
            assertFalse(basic.contains(tr("command.lsp.formatDocument")));
            assertFalse(basic.contains(tr("command.lsp.gotoImplementation")));
            menu.hide();

            buffer.setLspImplementationAvailable(true);
            buffer.setLspTypeDefinitionAvailable(true);
            buffer.setLspFormatAvailable(true);
            buffer.setLspCodeActionsAvailable(true);
            buffer.setLspRenameAvailable(true);
            area.moveTo(0);
            menu = rightClick(buffer, stop);
            int clicked = area.getCaretPosition();
            assertTrue(clicked == stop || clicked == stop + 1, "the caret went to the right-clicked symbol");
            area.moveTo(0); // each item re-targets the clicked spot itself
            for (String key : List.of(
                    "command.lsp.gotoDefinition",
                    "command.lsp.gotoImplementation",
                    "command.lsp.gotoTypeDefinition",
                    "command.lsp.findReferences",
                    "command.lsp.hover",
                    "command.lsp.codeActions",
                    "command.lsp.rename",
                    "command.lsp.formatDocument")) {
                EditorFx.menuItem(menu.getItems(), tr(key)).fire();
            }
            assertEquals(
                    List.of(
                            "definition@" + clicked,
                            "implementation@" + clicked,
                            "type@" + clicked,
                            "references@" + clicked,
                            "hover@" + clicked,
                            "actions@" + clicked,
                            "rename@" + clicked,
                            "format"),
                    ran);

            // A left click in the text dismisses the open menu.
            area.fireEvent(mouseAt(buffer, 3, true, false));
            assertFalse(menu.isShowing());

            // Ctrl-click is "go to definition" for the clicked symbol.
            ran.clear();
            area.moveTo(0);
            area.fireEvent(mouseAt(buffer, stop + 1, true, true));
            assertTrue(Math.abs(area.getCaretPosition() - (stop + 1)) <= 1, "the caret moved to the click");
            stage.close();

            buffer.setLspNavActions(null, null, null, null, null, null, null, null);
            buffer.setLspActive(false);
            assertFalse(buffer.isLspActive());
            buffer.dispose();
        });
    }

    @Test
    void ctrlClickFollowsAMarkdownLinkOrGoesToTheDefinition() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[2];
        Stage[] stages = new Stage[2];
        List<String> opened = new ArrayList<>();
        List<String> jumps = new ArrayList<>();
        String md = "see [docs](https://example.org/docs) here";
        String java = "class A { void run() {} }";
        EditorFx.onFx(() -> {
            EditorBuffer markdown = buffer("markdown", md);
            markdown.setOpenUrlHandler(opened::add);
            stages[0] = EditorFx.show(markdown, 600, 300);
            markdown.getArea().fireEvent(mouseAt(markdown, md.indexOf("docs]") + 1, true, true));
            assertEquals(List.of("https://example.org/docs"), opened);
            markdown.getArea().fireEvent(mouseAt(markdown, 1, true, true)); // on "see": not a link
            markdown.getArea().fireEvent(mouseAt(markdown, md.indexOf("docs]") + 1, true, false)); // plain click
            assertEquals(1, opened.size());
            ref[0] = markdown;

            EditorBuffer code = buffer("java", java);
            code.setLspNavActions(
                    () -> jumps.add("definition@" + code.getArea().getCaretPosition()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            stages[1] = EditorFx.show(code, 600, 300);
            code.getArea().fireEvent(mouseAt(code, java.indexOf("run") + 1, true, true));
            assertTrue(jumps.isEmpty(), "no language server: Ctrl-click is an ordinary click");
            code.setLspActive(true);
            code.getArea().moveTo(0);
            code.getArea().fireEvent(mouseAt(code, java.indexOf("run") + 1, true, true));
            ref[1] = code;
        });
        EditorFx.drain(); // go-to-definition is deferred so the caret and focus settle first
        EditorFx.onFx(() -> {
            assertEquals(1, jumps.size());
            int at = Integer.parseInt(jumps.get(0).substring("definition@".length()));
            assertTrue(Math.abs(at - (java.indexOf("run") + 1)) <= 1, "the action ran at the clicked symbol: " + at);
            stages[0].close();
            stages[1].close();
            ref[0].dispose();
            ref[1].dispose();
        });
    }

    @Test
    void theMenuGroupsMarkupBuildAiBookmarkAndNoteActions(@TempDir Path dir) throws Exception {
        EditorFx.onFx(() -> {
            String table = "| a | b |\n| --- | --- |\n| 1 | 2 |\n";
            EditorBuffer buffer = buffer("markdown", table);
            buffer.setPath(dir.resolve("notes.md"));
            List<String> ran = new ArrayList<>();
            buffer.setBuildMenuContributor(() -> List.of(new MenuItem("Maven: Package")));
            buffer.setMenuContributor(() -> List.of(new MenuItem("Plugin: Shout")));
            buffer.setAiActionHandlers(() -> ran.add("explain"), () -> ran.add("rewrite"));
            buffer.setAiActionsEnabled(true);
            buffer.setTableFileExporter((csv, format) -> ran.add("export:" + format));
            buffer.setBookmarkToggleRequest((b, line) -> ran.add("bookmark:" + line));
            buffer.setAddNoteHandler(b -> ran.add("note"));
            buffer.setNotesEnabled(true);
            Stage stage = EditorFx.show(buffer, 700, 420);
            CodeArea area = buffer.getArea();

            ContextMenu menu = rightClick(buffer, table.indexOf("1"));
            List<String> titles = EditorFx.titles(menu.getItems());
            assertTrue(titles.contains("Maven: Package"), titles.toString());
            assertTrue(titles.contains("Plugin: Shout"));
            assertTrue(titles.contains(tr("editmenu.markdown")));
            assertTrue(titles.contains(tr("editmenu.addBookmark")));
            assertTrue(titles.contains(tr("editmenu.addNote")));
            assertTrue(
                    titles.indexOf("Maven: Package") < titles.indexOf("Plugin: Shout"),
                    "build actions sit with the toolchain items, plugin items at the foot");

            MenuItem explain = EditorFx.menuItem(menu.getItems(), tr("command.ai.explainSelection"));
            assertTrue(explain.isDisable(), "nothing is selected");
            assertTrue(EditorFx.menuItem(menu.getItems(), tr("editmenu.cut")).isDisable());
            assertTrue(EditorFx.menuItem(menu.getItems(), tr("editmenu.copy")).isDisable());

            EditorFx.menuItem(menu.getItems(), tr("command.markdown.tableAlignRight"))
                    .fire();
            assertTrue(area.getText().lines().skip(1).findFirst().orElseThrow().contains("-:"), area.getText());
            EditorFx.menuItem(menu.getItems(), tr("command.markdown.tableAlignLeft"))
                    .fire();
            assertTrue(area.getText().lines().skip(1).findFirst().orElseThrow().contains(":-"), area.getText());
            EditorFx.menuItem(menu.getItems(), tr("command.markdown.tableAlignCenter"))
                    .fire();
            EditorFx.menuItem(menu.getItems(), tr("command.markdown.tableExportExcel"))
                    .fire();
            EditorFx.menuItem(menu.getItems(), tr("command.markdown.tableExportCsv"))
                    .fire();
            EditorFx.menuItem(menu.getItems(), tr("command.markdown.tableExportOds"))
                    .fire();
            EditorFx.menuItem(menu.getItems(), tr("editmenu.addBookmark")).fire();
            EditorFx.menuItem(menu.getItems(), tr("editmenu.addNote")).fire();
            assertEquals(List.of("export:xlsx", "export:csv", "export:ods", "bookmark:2", "note"), ran);
            menu.hide();

            // With a selection the AI and clipboard items wake up, and "Add Note" becomes selection-scoped.
            ran.clear();
            int cell = area.getText().indexOf('a');
            area.selectRange(cell, cell + 1); // the header cell "a"
            menu = rightClick(buffer, cell);
            assertEquals(1, area.getSelection().getLength(), "a right-click inside the selection keeps it");
            assertNotNull(EditorFx.findMenuItem(menu.getItems(), tr("editmenu.addNoteSelection")));
            EditorFx.menuItem(menu.getItems(), tr("command.ai.explainSelection"))
                    .fire();
            EditorFx.menuItem(menu.getItems(), tr("command.ai.rewriteSelection"))
                    .fire();
            assertEquals(List.of("explain", "rewrite"), ran);
            EditorFx.menuItem(menu.getItems(), tr("editmenu.copy")).fire();
            assertEquals("a", Clipboard.getSystemClipboard().getString());
            EditorFx.menuItem(menu.getItems(), tr("editmenu.cut")).fire();
            String withCell = area.getText();
            assertEquals(-1, area.getText().indexOf('a'), "the selected cell text was cut");
            EditorFx.menuItem(menu.getItems(), tr("editmenu.paste")).fire();
            assertEquals(withCell.replace("a", ""), area.getText().replace("a", ""));
            assertEquals(cell, area.getText().indexOf('a'), "and pasted back where it was");
            String pasted = area.getText();
            EditorFx.menuItem(menu.getItems(), tr("editmenu.undo")).fire();
            assertFalse(pasted.equals(area.getText()), "Undo took the last edit back");
            EditorFx.menuItem(menu.getItems(), tr("editmenu.redo")).fire();
            assertEquals(pasted, area.getText(), "and Redo restored it");
            EditorFx.menuItem(menu.getItems(), tr("editmenu.selectAll")).fire();
            assertEquals(area.getLength(), area.getSelection().getLength());
            menu.hide();

            // A read-only buffer keeps Copy but not Cut/Paste/Rewrite, and loses the Markdown submenu.
            buffer.setReadOnly(true);
            area.selectRange(cell, cell + 1);
            menu = rightClick(buffer, cell);
            assertFalse(EditorFx.menuItem(menu.getItems(), tr("editmenu.copy")).isDisable());
            assertTrue(EditorFx.menuItem(menu.getItems(), tr("editmenu.cut")).isDisable());
            assertTrue(EditorFx.menuItem(menu.getItems(), tr("editmenu.paste")).isDisable());
            assertTrue(EditorFx.menuItem(menu.getItems(), tr("command.ai.rewriteSelection"))
                    .isDisable());
            assertFalse(EditorFx.menuItem(menu.getItems(), tr("command.ai.explainSelection"))
                    .isDisable());
            assertNull(EditorFx.findMenuItem(menu.getItems(), tr("editmenu.markdown")));
            menu.hide();

            buffer.setReadOnly(false);
            buffer.setBuildMenuContributor(() -> List.of());
            buffer.setMenuContributor(() -> null);
            buffer.setAiActionsEnabled(false);
            buffer.setNotesEnabled(false);
            int one = area.getText().indexOf('1');
            area.moveTo(one);
            assertTrue(buffer.toggleBookmark(area.getCurrentParagraph()));
            menu = rightClick(buffer, one);
            titles = EditorFx.titles(menu.getItems());
            assertTrue(titles.contains(tr("editmenu.removeBookmark")), "a bookmarked line offers its removal");
            assertFalse(titles.contains(tr("editmenu.aiActions")));
            assertFalse(titles.contains(tr("editmenu.addNote")));
            menu.hide();
            stage.close();
            buffer.dispose();
        });
    }

    @Test
    void theTypstMenuFormatsAndAsksForATableOrAnImage() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("typst", "word here");
            List<String> ran = new ArrayList<>();
            Stage stage = EditorFx.show(buffer, 600, 300);
            CodeArea area = buffer.getArea();
            area.selectRange(0, 4);
            ContextMenu menu = rightClick(buffer, 1);
            assertNull(EditorFx.findMenuItem(menu.getItems(), tr("editmenu.addBookmark")), "an unsaved buffer");
            assertNotNull(EditorFx.findMenuItem(menu.getItems(), tr("editmenu.typst")));

            EditorFx.menuItem(menu.getItems(), tr("command.typst.insertImage")).fire(); // nothing wired
            EditorFx.menuItem(menu.getItems(), tr("command.typst.insertTable")).fire();
            buffer.setTypstImageHandler(() -> ran.add("image"));
            buffer.setInsertTypstTableHandler(() -> ran.add("table"));
            EditorFx.menuItem(menu.getItems(), tr("command.typst.insertImage")).fire();
            EditorFx.menuItem(menu.getItems(), tr("command.typst.insertTable")).fire();
            assertEquals(List.of("image", "table"), ran);

            EditorFx.menuItem(menu.getItems(), tr("command.typst.bold")).fire();
            assertEquals("*word* here", area.getText());
            EditorFx.menuItem(menu.getItems(), tr("command.typst.bold")).fire();
            assertEquals("word here", area.getText(), "the same item toggles it off");
            EditorFx.menuItem(menu.getItems(), tr("command.typst.emph")).fire();
            assertEquals("_word_ here", area.getText());
            EditorFx.menuItem(menu.getItems(), tr("command.typst.emph")).fire();
            EditorFx.menuItem(menu.getItems(), tr("command.typst.raw")).fire();
            assertEquals("`word` here", area.getText());
            EditorFx.menuItem(menu.getItems(), tr("command.typst.raw")).fire();
            EditorFx.menuItem(menu.getItems(), tr("command.typst.bulletList")).fire();
            assertEquals("- word here", area.getText());
            EditorFx.menuItem(menu.getItems(), tr("command.typst.bulletList")).fire();
            area.moveTo(0);
            EditorFx.menuItem(menu.getItems(), tr("command.typst.outline")).fire();
            assertTrue(area.getText().startsWith("#outline()"));
            EditorFx.clipboard("https://example.org/t");
            area.selectRange(area.getLength() - 4, area.getLength());
            EditorFx.menuItem(menu.getItems(), tr("command.typst.link")).fire();
            assertTrue(area.getText().endsWith("#link(\"https://example.org/t\")[here]"), area.getText());
            menu.hide();
            stage.close();
            buffer.dispose();
        });
    }

    @Test
    void hoveringALintSquiggleShowsItsMessage() throws Exception {
        EditorFx.onFx(() -> {
            // Mermaid: the validator's diagnostics drive the squiggle and its tooltip.
            EditorBuffer mermaid = buffer("mermaid", "graph TD\n  A --> B\n");
            mermaid.setMermaidValidator((text, sink) -> sink.accept(List.of(
                    new MaidOutput.Diagnostic(1, 1, 5, "error", "E1", "Unknown diagram type"),
                    new MaidOutput.Diagnostic(1, 1, 5, "error", "E2", "Second problem"))));
            Stage stage = EditorFx.show(mermaid, 600, 300);
            mermaid.setMermaidLintEnabled(true);
            mermaid.getArea().fireEvent(mouseAt(mermaid, 2, false, false));
            Tooltip tip = EditorFx.field(mermaid, "lintTooltip");
            assertNotNull(tip, "hovering the squiggle shows a tooltip");
            assertTrue(tip.isShowing());
            assertEquals("Unknown diagram type\nSecond problem", tip.getText());
            mermaid.getArea().fireEvent(mouseAt(mermaid, 3, false, false));
            assertTrue(tip.isShowing(), "the same message is not re-shown, it stays up");
            mermaid.getArea().fireEvent(mouseAt(mermaid, 14, false, false)); // line 2: no squiggle
            assertFalse(tip.isShowing());
            mermaid.getArea().fireEvent(mouseAt(mermaid, 2, false, false));
            assertTrue(tip.isShowing());
            mermaid.setMermaidLintEnabled(false);
            mermaid.getArea().fireEvent(mouseAt(mermaid, 2, false, false));
            assertFalse(tip.isShowing(), "linting off: no tooltip");
            mermaid.setMermaidLintEnabled(true);
            mermaid.getArea().fireEvent(mouseAt(mermaid, 2, false, false));
            assertTrue(tip.isShowing());
            mermaid.getArea()
                    .fireEvent(new MouseEvent(
                            MouseEvent.MOUSE_EXITED,
                            0,
                            0,
                            0,
                            0,
                            javafx.scene.input.MouseButton.NONE,
                            0,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            null));
            assertFalse(tip.isShowing(), "leaving the editor hides it");
            stage.close();
            mermaid.dispose();

            // Markdown: the message carries the rule code.
            EditorBuffer markdown = buffer("markdown", "#Heading\n\ntext\n");
            markdown.setMarkdownLintValidator((text, sink) ->
                    sink.accept(List.of(new MarkdownLint.Diagnostic(1, 1, 8, "warning", "MD018", "No space after #"))));
            Stage mdStage = EditorFx.show(markdown, 600, 300);
            markdown.setMarkdownLintEnabled(true);
            markdown.getArea().fireEvent(mouseAt(markdown, 3, false, false));
            Tooltip mdTip = EditorFx.field(markdown, "mdLintTooltip");
            assertNotNull(mdTip);
            assertTrue(mdTip.isShowing());
            assertEquals("MD018: No space after #", mdTip.getText());
            markdown.getArea().fireEvent(mouseAt(markdown, 4, false, false));
            assertTrue(mdTip.isShowing());
            markdown.getArea().fireEvent(mouseAt(markdown, 11, false, false)); // "text": clean
            assertFalse(mdTip.isShowing());
            markdown.getArea().fireEvent(mouseAt(markdown, 3, false, false));
            markdown.setMarkdownLintEnabled(false);
            markdown.getArea().fireEvent(mouseAt(markdown, 3, false, false));
            assertFalse(mdTip.isShowing());
            mdStage.close();
            markdown.dispose();
        });
    }

    @Test
    void hoveringAnIdentifierWhileSuspendedShowsItsValue() throws Exception {
        EditorFx.onFx(() -> {
            String source = "int total = count + 1;";
            EditorBuffer buffer = buffer("java", source);
            List<String> asked = new ArrayList<>();
            java.util.Map<String, String> values = new java.util.HashMap<>();
            values.put("total", "42");
            values.put("count", " ");
            buffer.setDebugHoverEvaluator((word, sink) -> {
                asked.add(word);
                sink.accept(values.get(word));
            });
            Stage stage = EditorFx.show(buffer, 600, 300);
            buffer.getArea().fireEvent(mouseAt(buffer, source.indexOf("total") + 1, false, false));
            assertTrue(asked.isEmpty(), "not suspended: hovering evaluates nothing");

            buffer.setDebugHoverActive(true);
            buffer.getArea().fireEvent(mouseAt(buffer, source.indexOf("total") + 1, false, false));
            Tooltip tip = EditorFx.field(buffer, "debugTooltip");
            assertNotNull(tip);
            assertTrue(tip.isShowing());
            assertEquals("total = 42", tip.getText());
            buffer.getArea().fireEvent(mouseAt(buffer, source.indexOf("total") + 2, false, false));
            assertEquals(List.of("total"), asked, "one evaluation for each hovered word");

            buffer.getArea().fireEvent(mouseAt(buffer, source.indexOf("count") + 1, false, false));
            assertEquals(List.of("total", "count"), asked);
            assertEquals("total = 42", tip.getText(), "a blank value shows nothing new");

            buffer.getArea().fireEvent(mouseAt(buffer, source.indexOf("="), false, false)); // not an identifier
            assertFalse(tip.isShowing());
            buffer.getArea().fireEvent(mouseAt(buffer, source.indexOf("total") + 1, false, false));
            assertTrue(tip.isShowing(), "back on the word: evaluated again");
            buffer.setDebugHoverActive(false);
            assertFalse(tip.isShowing(), "resuming hides the value");
            stage.close();
            buffer.dispose();
        });
    }

    private static <T extends Node> T find(Node root, Class<T> type, String styleClass) {
        Node found = root.lookup("." + styleClass);
        assertNotNull(found, "no ." + styleClass + " under the buffer");
        return type.cast(found);
    }

    @Test
    void theInstallBannerRunsItsActionsAndShowsProgress() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("java", "class A {}");
            List<String> ran = new ArrayList<>();
            buffer.showInstallBar(true);
            assertFalse(buffer.isInstallBarShown(), "there is no prompt to show yet");
            buffer.setInstallBarBusy(true); // nothing to mark busy either

            buffer.setInstallPrompt("Install the Java language server?", "Install", () -> ran.add("install"), null);
            buffer.showInstallBar(true);
            assertTrue(buffer.isInstallBarShown());
            Stage stage = EditorFx.show(buffer, 700, 300);
            Node bar = find(buffer.getNode(), Node.class, "lsp-install-bar");
            assertTrue(EditorFx.textOf(bar).contains("Install the Java language server?"));
            Button install = find(bar, Button.class, "accent");
            Button dismiss = find(bar, Button.class, "lsp-install-dismiss");
            assertEquals("Install", install.getText());
            install.fire();
            dismiss.fire(); // no dismiss action wired: nothing happens
            assertEquals(List.of("install"), ran);

            buffer.setInstallBarBusy(true);
            assertTrue(install.isDisable());
            assertTrue(dismiss.isDisable());
            ProgressIndicator progress = EditorFx.field(buffer, "installProgress");
            assertTrue(progress.isVisible());
            buffer.setInstallBarBusy(false);
            assertFalse(install.isDisable());
            assertFalse(progress.isVisible());

            // The same banner is reused for the next prompt.
            buffer.setInstallPrompt("Install the debugger?", "Get it", null, () -> ran.add("dismiss"));
            assertEquals("Get it", install.getText());
            install.fire();
            dismiss.fire();
            assertEquals(List.of("install", "dismiss"), ran);

            buffer.showInstallBar(false);
            assertFalse(buffer.isInstallBarShown());
            assertNull(buffer.getNode().lookup(".lsp-install-bar"), "the banner left the tab");

            // Together with the read-only banner the two stack.
            buffer.setViewMode(true);
            buffer.showInstallBar(true);
            assertNotNull(buffer.getNode().lookup(".lsp-install-bar"));
            assertTrue(buffer.isViewMode());
            stage.close();
            buffer.dispose();
        });
    }

    @Test
    void aViewModeBufferPagesWithSpaceAndBackspace() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 400; i++) {
                sb.append("line ").append(i).append('\n');
            }
            EditorBuffer buffer = buffer("java", sb.toString());
            stage[0] = EditorFx.show(buffer, 600, 300);
            ref[0] = buffer;
        });
        EditorFx.drain();
        EditorFx.onFx(() -> {
            EditorBuffer buffer = ref[0];
            CodeArea area = buffer.getArea();
            // Filling the buffer left the view at the end of the text with the caret back on line 0, out of
            // sight. A page is measured from the caret's place on screen, so with no caret on screen Space
            // only scrolls; and at the last screenful it jumps to the end of the text instead, which is what
            // this test used to mistake for a page (and only where the last line happened to be fully
            // visible, which depends on the line height of whichever user-agent stylesheet is in force).
            area.showParagraphAtTop(0);
            buffer.getNode().layout();
            assertEquals(0, area.firstVisibleParToAllParIndex(), "the view starts at the top, on the caret");
            int screenful = area.getVisibleParagraphs().size();
            assertTrue(screenful > 3 && screenful < 40, "a 300px view shows some lines, not all 400: " + screenful);
            area.fireEvent(EditorFx.pressed(KeyCode.SPACE, false, false));
            assertEquals(0, area.getCurrentParagraph(), "an editable buffer does not page on Space");

            buffer.setViewMode(true);
            assertFalse(buffer.isEditable());
            area.fireEvent(EditorFx.pressed(KeyCode.SPACE, false, false));
            int afterSpace = area.getCurrentParagraph();
            assertTrue(
                    Math.abs(afterSpace - screenful) <= 1,
                    "Space pages down one screenful of " + screenful + " lines, was line " + afterSpace);
            area.fireEvent(EditorFx.pressed(KeyCode.SPACE, false, true)); // Ctrl-Space is the keymap's
            area.fireEvent(EditorFx.pressed(KeyCode.A, false, false));
            assertEquals(afterSpace, area.getCurrentParagraph());
            area.fireEvent(EditorFx.pressed(KeyCode.BACK_SPACE, false, false));
            assertTrue(area.getCurrentParagraph() < afterSpace, "Backspace pages back up");
            assertTrue(area.getText().endsWith("line 399\n"), "and nothing was deleted");
            stage[0].close();
            buffer.dispose();
        });
    }
}
