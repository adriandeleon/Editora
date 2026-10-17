package com.editora.ui;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which settings changes re-apply only the buffers' view (pure: no toolkit). */
class SettingsReapplyTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theChangedKeysAreTheTopLevelSettingsThatDiffer() throws Exception {
        var before = json.readTree("{\"tabSize\":4,\"showMinimap\":true,\"rulers\":[80],\"gone\":1}");
        var now = json.readTree("{\"tabSize\":8,\"showMinimap\":true,\"rulers\":[80,120],\"added\":2}");
        assertEquals(Set.of("tabSize", "rulers", "gone", "added"), SettingsReapply.changedKeys(before, now));
    }

    @Test
    void onlyAKnownNonEmptySetOfViewSettingsSkipsTheFullReapply() {
        assertTrue(SettingsReapply.viewOnly(Set.of("showMinimap")));
        assertTrue(SettingsReapply.viewOnly(Set.of("tabSize", "showLineNumbers")));
        assertFalse(SettingsReapply.viewOnly(Set.of("showMinimap", "gitSupport")), "a feature switch reaches more");
        assertFalse(SettingsReapply.viewOnly(Set.of("theme")));
        assertFalse(SettingsReapply.viewOnly(Set.of("fontSize")), "consoles and panels read the font too");
        assertFalse(SettingsReapply.viewOnly(Set.of()), "nothing changed: a keymap or plugin reload, re-apply all");
        assertFalse(SettingsReapply.viewOnly(null), "the first re-apply knows nothing yet");
    }
}
