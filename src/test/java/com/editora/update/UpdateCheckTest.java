package com.editora.update;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DAY = UpdateCheck.DEFAULT_INTERVAL_MS;

    private static ReleaseInfo parse(String json) {
        return UpdateCheck.parseLatest(MAPPER, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesTagUrlAndNameFromAReleasePayload() {
        ReleaseInfo r = parse("""
                {"tag_name":"v1.2.0","name":"Release 1.2.0","html_url":"https://github.com/o/r/releases/tag/v1.2.0",
                 "draft":false,"prerelease":false}""");
        assertNotNull(r);
        assertEquals("1.2.0", r.version(), "leading v stripped");
        assertEquals("https://github.com/o/r/releases/tag/v1.2.0", r.url());
        assertEquals("Release 1.2.0", r.name());
    }

    @Test
    void draftsAndPrereleasesAreIgnored() {
        assertNull(parse("{\"tag_name\":\"v2.0.0\",\"draft\":true}"), "draft → null");
        assertNull(parse("{\"tag_name\":\"v2.0.0-rc1\",\"prerelease\":true}"), "prerelease → null");
    }

    @Test
    void missingTagOrMalformedJsonYieldsNull() {
        assertNull(parse("{\"name\":\"no tag here\"}"), "no tag_name → null");
        assertNull(parse("not json at all"), "garbage → null (never throws)");
        assertNull(parse("[1,2,3]"), "non-object → null");
    }

    @Test
    void normalizeVersionStripsLeadingV() {
        assertEquals("0.9.5", UpdateCheck.normalizeVersion("v0.9.5"));
        assertEquals("0.9.5", UpdateCheck.normalizeVersion("V0.9.5"));
        assertEquals("0.9.5", UpdateCheck.normalizeVersion("0.9.5"));
        assertEquals("", UpdateCheck.normalizeVersion(""));
        assertEquals("", UpdateCheck.normalizeVersion(null));
    }

    @Test
    void isNewerComparesSemver() {
        assertTrue(UpdateCheck.isNewer("0.9.5", "0.9.6"));
        assertTrue(UpdateCheck.isNewer("0.9.5", "0.10.0"), "0.10 > 0.9 numerically, not lexically");
        assertTrue(UpdateCheck.isNewer("0.9.5", "1.0.0"));
        assertFalse(UpdateCheck.isNewer("0.9.5", "0.9.5"), "same version is not newer");
        assertFalse(UpdateCheck.isNewer("0.9.5", "0.9.4"), "older is not newer");
        assertTrue(UpdateCheck.isNewer("0.0.0", "0.1.0"), "dev fallback 0.0.0 surfaces the real latest");
        assertFalse(UpdateCheck.isNewer("0.9.5", null));
        assertFalse(UpdateCheck.isNewer("0.9.5", "  "));
    }

    @Test
    void isDueRespectsTheInterval() {
        long now = 1_000_000_000_000L;
        assertTrue(UpdateCheck.isDue(0, now, DAY), "never checked → due");
        assertTrue(UpdateCheck.isDue(now - DAY, now, DAY), "exactly a day elapsed → due");
        assertTrue(UpdateCheck.isDue(now - 2 * DAY, now, DAY), "two days → due");
        assertFalse(UpdateCheck.isDue(now - DAY / 2, now, DAY), "half a day → not yet");
        assertTrue(UpdateCheck.isDue(now + DAY, now, DAY), "future timestamp (clock moved back) → due");
    }
}
