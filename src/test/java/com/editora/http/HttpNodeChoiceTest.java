package com.editora.http;

import java.util.List;

import com.editora.run.NodeRuntimes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review E6: a blank "Node.js for HTTP scripts" runs the Node.js every other Node feature uses. */
class HttpNodeChoiceTest {

    private static final NodeRuntimes.NodeRuntime SHARED =
            new NodeRuntimes.NodeRuntime(List.of("/opt/node-22/bin/node"), NodeRuntimes.Source.SETTING, null);

    @Test
    void theHttpFieldWinsWhenItNamesANode() {
        assertEquals("/usr/local/bin/node", HttpNodeChoice.command(" /usr/local/bin/node ", () -> SHARED));
        assertTrue(HttpNodeChoice.explicit("/usr/local/bin/node"));
    }

    @Test
    void blankOrBareNodeMeansTheSharedNode() {
        assertEquals("/opt/node-22/bin/node", HttpNodeChoice.command("", () -> SHARED));
        assertEquals("/opt/node-22/bin/node", HttpNodeChoice.command(null, () -> SHARED));
        assertEquals("/opt/node-22/bin/node", HttpNodeChoice.command("node", () -> SHARED));
        assertFalse(HttpNodeChoice.explicit("  "));
        assertEquals("node", HttpNodeChoice.command("", () -> null));
    }

    @Test
    void theSettingsPageShowsWithoutTouchingTheDisk() {
        assertEquals("/x/node", HttpNodeChoice.displayed("/x/node", "/opt/node"));
        assertEquals("/opt/node", HttpNodeChoice.displayed("", "/opt/node"));
        assertEquals("node", HttpNodeChoice.displayed("", ""));
        assertEquals("node", HttpNodeChoice.displayed(null, null));
    }
}
