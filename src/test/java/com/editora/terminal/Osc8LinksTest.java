package com.editora.terminal;

import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.hyperlinks.LinkResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Osc8LinksTest {

    @Test
    void onlySchemesThatOpenSafelyAreLinks() {
        assertTrue(Osc8Links.allowed("https://example.com"));
        assertTrue(Osc8Links.allowed("HTTP://example.com"));
        assertTrue(Osc8Links.allowed("file:///etc/hosts"));
        assertTrue(Osc8Links.allowed("mailto:me@example.com"));
        assertFalse(Osc8Links.allowed("javascript:alert(1)"));
        assertFalse(Osc8Links.allowed("vscode://open"));
        assertFalse(Osc8Links.allowed("no scheme"));
        assertFalse(Osc8Links.allowed(""));
        assertFalse(Osc8Links.allowed("https://" + "a".repeat(3000)));
    }

    @Test
    void theFilterKeepsTheUriInTheStyleTheViewReadsBack() {
        LinkResult result = Osc8Links.filter().apply("https://example.com/x");
        assertNotNull(result);
        var item = result.getItems().getFirst();
        assertEquals(0, item.getStartOffset());
        assertEquals("https://example.com/x".length(), item.getEndOffset());
        HyperlinkStyle style = new HyperlinkStyle(TextStyle.EMPTY, item.getLinkInfo());
        assertEquals("https://example.com/x", Osc8Links.targetOf(style).uri());
        assertNull(Osc8Links.filter().apply("javascript:x"));
        assertNull(Osc8Links.targetOf(TextStyle.EMPTY));
    }
}
