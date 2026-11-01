package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The built-in XML formatter's guards (XML/pom review 3, C29). */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class XmlFormatCoordinatorFxTest {

    private final List<String> status = new CopyOnWriteArrayList<>();

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private XmlFormatCoordinator coordinator(EditorBuffer buffer) {
        return coordinator(() -> buffer);
    }

    private XmlFormatCoordinator coordinator(java.util.function.Supplier<EditorBuffer> active) {
        return new XmlFormatCoordinator(new PythonFormatCoordinator.Host() {
            @Override
            public @Nullable EditorBuffer activeBuffer() {
                return active.get();
            }

            @Override
            public boolean activeEditable() {
                return true;
            }

            @Override
            public boolean serverFormats(Path file) {
                return false;
            }

            @Override
            public @Nullable Path venvOf(@Nullable Path file) {
                return null;
            }

            @Override
            public void setStatus(String message) {
                status.add(message);
            }

            @Override
            public void setError(@Nullable String message) {
                status.add("error:" + message);
            }
        });
    }

    private static EditorBuffer xml(String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("xml");
            b.setContent(text);
            b.getNode();
            return b;
        });
    }

    private void waitForStatus(String message) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (!status.contains(message) && System.currentTimeMillis() < until) {
            FxTestSupport.runOnFx(() -> {});
            Thread.sleep(20);
        }
        assertTrue(status.contains(message), String.valueOf(status));
    }

    /** Two Format Document runs 50 ms apart: the second said "Not formatted: the file changed" after the first. */
    @Test
    void aSecondFormatWhileOneRunsSaysSo() throws Exception {
        status.clear();
        EditorBuffer b = xml("<a><b>x</b><c/></a>");
        XmlFormatCoordinator f = coordinator(b);
        FxTestSupport.runOnFx(() -> {
            f.formatActive();
            f.formatActive();
        });
        waitForStatus(tr("status.xml.formatted"));
        assertTrue(status.contains(tr("status.xml.formatRunning")), String.valueOf(status));
        assertTrue(!status.contains(tr("status.lsp.formatStale")), String.valueOf(status));
        assertEquals("<a>\n    <b>x</b>\n    <c/>\n</a>", FxTestSupport.callOnFx(b::getContent));

        status.clear();
        FxTestSupport.runOnFx(() -> f.formatActive()); // done: the next one runs
        waitForStatus(tr("status.lsp.formatNoChange"));
    }

    /** HTML with no server formatting it gets the built-in formatter, which keeps a {@code <pre>} (HTML review C17). */
    @Test
    void htmlWithoutAServerGetsTheBuiltInFormatter() throws Exception {
        status.clear();
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer h = new EditorBuffer();
            h.setLanguageOverride("html");
            h.setContent("<body><div><p>x</p></div><pre>\n  a   b\n</pre></body>\n");
            h.getNode();
            return h;
        });
        XmlFormatCoordinator f = coordinator(b);
        FxTestSupport.runOnFx(() -> f.formatActive());
        waitForStatus(tr("status.html.formatted"));
        String indent = FxTestSupport.callOnFx(b::indentUnit);
        assertEquals(
                "<body>\n" + indent + "<div>\n" + indent + indent + "<p>x</p>\n" + indent + "</div>\n" + indent
                        + "<pre>\n  a   b\n</pre>\n</body>\n",
                FxTestSupport.callOnFx(b::getContent));

        status.clear();
        EditorBuffer open = FxTestSupport.callOnFx(() -> {
            EditorBuffer h = new EditorBuffer();
            h.setLanguageOverride("html");
            h.setContent("<div><script>var a;");
            h.getNode();
            return h;
        });
        FxTestSupport.runOnFx(() -> coordinator(open).formatActive());
        waitForStatus("error:" + tr("status.html.formatOpenConstruct"));
    }

    /**
     * A Django template saved as {@code .html} is not reflowed: the formatter would split its <code>{# #}</code>
     * comment and nest its {@code {% if %}} branches (HTML review 2 C108). The text stays as it was.
     */
    @Test
    void anHtmlTemplateIsNotFormatted() throws Exception {
        status.clear();
        String django = "<body>\n  {# <div> a commented-out wrapper #}\n  {% if user %}\n<div class=\"a\">\n"
                + "  {% else %}\n<div class=\"b\">\n  {% endif %}\n</div></body>\n";
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer h = new EditorBuffer();
            h.setLanguageOverride("html");
            h.setContent(django);
            h.getNode();
            return h;
        });
        FxTestSupport.runOnFx(() -> coordinator(b).formatActive());
        waitForStatus("error:" + tr("status.html.formatTemplate"));
        assertEquals(django, FxTestSupport.callOnFx(b::getContent));
    }

    /** In large-file mode a format that multiplies the line count asks first; Cancel leaves the file as it was. */
    @Test
    void aLargeFileFormatThatMultipliesTheLinesAsksFirst() throws Exception {
        status.clear();
        String text = "<a><b>x</b><b>y</b><b>z</b></a>";
        EditorBuffer b = xml(text);
        FxTestSupport.runOnFx(() -> b.setLargeFile(true));
        XmlFormatCoordinator f = coordinator(b);
        List<long[]> asked = new CopyOnWriteArrayList<>();
        f.setAskGrowthForTest((buffer, lines) -> {
            asked.add(lines);
            return false;
        });
        FxTestSupport.runOnFx(() -> f.formatActive());
        waitForStatus(tr("status.xml.formatSkipped"));
        assertEquals(1, asked.size());
        assertArrayEquals(new long[] {1, 5}, asked.get(0));
        assertEquals(text, FxTestSupport.callOnFx(b::getContent));
    }

    /**
     * XML/pom review 4, C35: switching tabs mid-format discarded it as "the file changed", and Format Document in the
     * other tab said "Already formatting". Each tab now formats on its own, and the first goes into its own tab.
     */
    @Test
    void switchingTabsMidFormatStillFormatsBothFiles() throws Exception {
        status.clear();
        EditorBuffer first = xml("<a><b>x</b></a>");
        EditorBuffer second = xml("<c><d>y</d></c>");
        EditorBuffer[] active = {first};
        XmlFormatCoordinator f = coordinator(() -> active[0]);
        FxTestSupport.runOnFx(() -> {
            f.formatActive();
            active[0] = second; // the user switches tabs while the first one formats
            f.formatActive();
        });
        waitForStatus(tr("status.xml.formatted"));
        waitForStatus(tr("status.xml.formattedOther", FxTestSupport.callOnFx(first::getTitle)));
        assertTrue(!status.contains(tr("status.xml.formatRunning")), String.valueOf(status));
        assertTrue(!status.contains(tr("status.lsp.formatStale")), String.valueOf(status));
        assertEquals("<a>\n    <b>x</b>\n</a>", FxTestSupport.callOnFx(first::getContent));
        assertEquals("<c>\n    <d>y</d>\n</c>", FxTestSupport.callOnFx(second::getContent));
    }

    /** XML/pom review 4, C34: the large-file question read "Format ?" for every saved file. */
    @Test
    void theLargeFileQuestionNamesTheFile() throws Exception {
        EditorBuffer b = xml("<a/>");
        FxTestSupport.runOnFx(() -> b.setPath(Path.of("data", "big.xml")));
        assertEquals(
                tr("dialog.xml.formatGrowth.header", "big.xml"),
                FxTestSupport.callOnFx(() -> XmlFormatCoordinator.growthHeader(b)));
    }
}
