package com.editora.ui;

import java.net.URL;
import java.util.List;

import javafx.css.CssParser;
import javafx.css.Declaration;
import javafx.css.ParsedValue;
import javafx.css.Rule;
import javafx.css.Stylesheet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build precompiles every JavaFX stylesheet to its binary form ({@code scripts/css2bss.java}, run at
 * {@code process-classes}), beside the {@code .css}. JavaFX looks for {@code name.bss} before parsing
 * {@code name.css}, so what ships must be there, must load, and must say exactly what the text says — and
 * resolve a relative {@code url()} from the same place, which is why it sits beside the text.
 *
 * <p>Pure: the CSS parser and the binary reader need no toolkit.
 */
class StylesheetBinaryTest {

    private static final List<String> PRECOMPILED = List.of(
            "/com/editora/styles/app.css",
            "/com/editora/styles/syntax.css",
            "/com/editora/styles/ui-font.css",
            "/com/editora/styles/atlantafx-themes/editora-light.css",
            "/com/editora/styles/atlantafx-themes/editora-dark.css",
            "/com/editora/styles/editor-themes/editora-light.css");

    private static String bssPath(String css) {
        return css.substring(0, css.length() - 4) + ".bss";
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/com/editora/styles/app.css",
                "/com/editora/styles/syntax.css",
                "/com/editora/styles/ui-font.css",
                "/com/editora/styles/atlantafx-themes/editora-light.css",
                "/com/editora/styles/atlantafx-themes/editora-dark.css",
                "/com/editora/styles/editor-themes/editora-light.css"
            })
    void theBinaryFormIsBundledBesideTheTextAndSaysTheSame(String css) throws Exception {
        URL cssUrl = StylesheetBinaryTest.class.getResource(css);
        URL bssUrl = StylesheetBinaryTest.class.getResource(bssPath(css));
        assertNotNull(cssUrl, css);
        assertNotNull(bssUrl, "no .bss beside " + css + " — did the css-to-bss build step run?");

        Stylesheet text = new CssParser().parse(cssUrl);
        Stylesheet binary = Stylesheet.loadBinary(bssUrl);
        assertNotNull(binary, "the binary form did not load (a JavaFX version mismatch rejects it)");
        assertEquals(bssUrl.toExternalForm(), binary.getUrl(), "url() values resolve against the .bss location");
        assertEquals(
                cssUrl.toExternalForm().replace(".css", ".bss"),
                binary.getUrl(),
                "the .bss must sit beside the .css so a relative url() means the same file");

        assertEquals(text.getRules().size(), binary.getRules().size(), "rule count");
        for (int i = 0; i < text.getRules().size(); i++) {
            Rule t = text.getRules().get(i);
            Rule b = binary.getRules().get(i);
            assertEquals(t.getSelectors().toString(), b.getSelectors().toString(), "selectors of rule " + i);
            assertEquals(t.getDeclarations().size(), b.getDeclarations().size(), "declarations of rule " + i);
            for (int d = 0; d < t.getDeclarations().size(); d++) {
                Declaration td = t.getDeclarations().get(d);
                Declaration bd = b.getDeclarations().get(d);
                assertEquals(td.getProperty(), bd.getProperty(), "property " + d + " of rule " + i);
                assertEquals(td.isImportant(), bd.isImportant(), td.getProperty() + " !important");
                assertSameValue(td.getProperty() + " in rule " + i, td.getParsedValue(), bd.getParsedValue());
            }
        }
    }

    /**
     * Structural equality of two parsed values: {@code ParsedValue.toString} prints nested arrays by identity,
     * so the tree is walked. A {@code url()} carries its stylesheet's URL beside the path, which is the one
     * place the two legitimately differ — by the extension.
     */
    private static void assertSameValue(String what, ParsedValue<?, ?> t, ParsedValue<?, ?> b) {
        if (t == null || b == null) {
            assertEquals(t, b, what);
            return;
        }
        assertEquals(
                t.getConverter() == null ? null : t.getConverter().getClass(),
                b.getConverter() == null ? null : b.getConverter().getClass(),
                what + " converter");
        Object tv = t.getValue();
        Object bv = b.getValue();
        if (tv instanceof ParsedValue<?, ?>[] ta) {
            assertTrue(bv instanceof ParsedValue<?, ?>[], what + " value shape");
            ParsedValue<?, ?>[] ba = (ParsedValue<?, ?>[]) bv;
            assertEquals(ta.length, ba.length, what + " value count");
            for (int i = 0; i < ta.length; i++) {
                assertSameValue(what + "[" + i + "]", ta[i], ba[i]);
            }
        } else if (tv instanceof ParsedValue<?, ?> tp) {
            assertTrue(bv instanceof ParsedValue<?, ?>, what + " value shape");
            assertSameValue(what + "/", tp, (ParsedValue<?, ?>) bv);
        } else if (tv instanceof ParsedValue<?, ?>[][] tm) {
            assertTrue(bv instanceof ParsedValue<?, ?>[][], what + " value shape");
            ParsedValue<?, ?>[][] bm = (ParsedValue<?, ?>[][]) bv;
            assertEquals(tm.length, bm.length, what + " row count");
            for (int r = 0; r < tm.length; r++) {
                assertEquals(tm[r].length, bm[r].length, what + " row " + r + " count");
                for (int i = 0; i < tm[r].length; i++) {
                    assertSameValue(what + "[" + r + "][" + i + "]", tm[r][i], bm[r][i]);
                }
            }
        } else {
            String ts = String.valueOf(tv);
            String bs = String.valueOf(bv);
            if (ts.endsWith(".css") && bs.endsWith(".bss")) {
                ts = ts.substring(0, ts.length() - 4);
                bs = bs.substring(0, bs.length() - 4);
            }
            assertEquals(ts, bs, what);
        }
    }

    @Test
    void everyBundledThemeAndEditorThemeIsPrecompiled() {
        for (String[] theme : Themes.BUNDLED.values()) {
            String css = "/com/editora/styles/atlantafx-themes/" + theme[0] + ".css";
            assertNotNull(StylesheetBinaryTest.class.getResource(bssPath(css)), css + " has no .bss");
        }
        for (String css : PRECOMPILED) {
            assertNotNull(StylesheetBinaryTest.class.getResource(bssPath(css)), css + " has no .bss");
        }
    }

    @Test
    void theHtmlExportSheetIsNotMistakenForJavaFxCss() {
        assertNotNull(StylesheetBinaryTest.class.getResource("/com/editora/styles/markdown-export.css"));
        assertNull(
                StylesheetBinaryTest.class.getResource("/com/editora/styles/markdown-export.bss"),
                "markdown-export.css styles exported HTML pages; a JavaFX binary of it would be garbage");
    }

    @Test
    void aBundledThemeReportsItsBinaryForm() {
        String bss = Themes.themeFor("Editora Dark").getUserAgentStylesheetBSS();
        assertNotNull(bss);
        assertTrue(bss.endsWith("/atlantafx-themes/editora-dark.bss"), bss);
        assertNull(Themes.binaryStylesheetFor("file:/home/someone/.editora/themes/mine.css"), "a user theme has none");
        assertNull(Themes.binaryStylesheetFor("/com/editora/styles/no-such.css"));
        assertNull(Themes.binaryStylesheetFor(null));
    }
}
