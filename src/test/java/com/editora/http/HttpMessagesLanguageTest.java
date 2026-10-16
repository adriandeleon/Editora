package com.editora.http;

import java.util.List;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the HTTP Client says about a request reads in the UI language (Q13), not always in English. */
class HttpMessagesLanguageTest {

    @AfterEach
    void english() {
        Messages.init("en"); // the catalog is process-wide; leave it as the other tests expect
    }

    @Test
    void aDroppedHeaderIsReportedInTheUiLanguage() {
        Messages.init("de");
        List<String> warnings = HttpHeaders.partition(List.<String[]>of(new String[] {"Connection", "close"}))
                .warnings();
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("Header „Connection“ wurde nicht gesendet"), warnings.get(0));
    }

    @Test
    void aCancelledRunReadsInTheUiLanguageWhileItsMarkerStaysTheSame() {
        HttpResult cancelled = HttpResult.failure(HttpClientService.CANCELLED, List.of());
        assertEquals("request cancelled", cancelled.shownError());
        Messages.init("fr");
        assertEquals("requête annulée", cancelled.shownError());
        assertEquals(HttpClientService.CANCELLED, cancelled.error(), "compared and saved as is");
    }

    @Test
    void aBodyWithoutItsBlankLineIsWarnedInTheUiLanguage() {
        Messages.init("es");
        HttpFile.Parsed p = HttpFile.parseRequest("POST http://x.test/\nContent-Type: application/json\n{\"a\": 1}\n");
        assertTrue(
                p.warnings().get(0).startsWith("el cuerpo de una petición"),
                p.warnings().toString());
    }
}
