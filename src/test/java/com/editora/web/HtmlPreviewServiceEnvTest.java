package com.editora.web;

import java.util.List;

import com.editora.process.ChildEnv;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The browser a live preview opens runs in the user's locale, not the {@code LC_ALL=C} meant for parsing. */
class HtmlPreviewServiceEnvTest {

    @Test
    void theBrowserInheritsTheUsersLocaleAndIsDetached() {
        ProcessBuilder pb = HtmlPreviewService.browserProcess(List.of("/opt/browser/browser", "http://127.0.0.1:1/x"));

        assertTrue(
                ChildEnv.inheritsUserLocale(pb.environment()),
                "LC_ALL=" + pb.environment().get("LC_ALL"));
        assertEquals(ProcessBuilder.Redirect.DISCARD, pb.redirectOutput(), "a foreground browser must not stall us");
        assertEquals(ProcessBuilder.Redirect.DISCARD, pb.redirectError());
    }
}
