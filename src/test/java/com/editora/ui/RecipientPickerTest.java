package com.editora.ui;

import java.util.List;

import com.editora.crypto.GpgKey;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recipient picker's decisions that need no scene: the order, the search, and the words for a key. */
class RecipientPickerTest {

    static GpgKey key(String fpr, String uid, char validity, String caps, boolean secret) {
        return key(fpr, uid, validity, caps, secret, 0);
    }

    static GpgKey key(String fpr, String uid, char validity, String caps, boolean secret, long expires) {
        return new GpgKey(
                fpr,
                fpr.substring(fpr.length() - 16),
                uid.isEmpty() ? List.of() : List.of(uid),
                validity,
                caps,
                expires,
                secret,
                List.of(),
                List.of());
    }

    private static final GpgKey ME = key("A".repeat(40), "Ada Lovelace <ada@example.org>", 'u', "ESCA", true);
    private static final GpgKey DANA = key("B".repeat(40), "Dana Ruiz <dana@example.org>", 'f', "E", false);
    private static final GpgKey ZED = key("C".repeat(40), "zed <zed@example.org>", '-', "E", false);
    private static final GpgKey BOB = key("D".repeat(40), "Bob Brown <bob@example.org>", '-', "E", false);
    private static final GpgKey GONE = key("E".repeat(40), "Gone Away <gone@example.org>", 'r', "E", false);

    @Test
    void yourKeysComeFirstThenRecentThenByNameAndUnusableKeysLast() {
        List<GpgKey> keys = List.of(GONE, ZED, DANA, BOB, ME);
        assertEquals(List.of(ME, BOB, DANA, ZED, GONE), RecipientPicker.ordered(keys, List.of()));
        assertEquals(
                List.of(ME, ZED, DANA, BOB, GONE),
                RecipientPicker.ordered(keys, List.of(ZED.fingerprint(), DANA.fingerprint())),
                "recent keys come next, newest first");
    }

    @Test
    void searchMatchesNamesEmailsAndKeyIdsIgnoringCaseAndSpaces() {
        assertTrue(RecipientPicker.matches(DANA, null), "no query matches everything");
        assertTrue(RecipientPicker.matches(DANA, "   "));
        assertTrue(RecipientPicker.matches(DANA, "danaruiz"), "spaces are ignored");
        assertTrue(RecipientPicker.matches(DANA, "DANA@EXAMPLE"));
        assertTrue(RecipientPicker.matches(DANA, "0xbbbb"), "a 0x key id matches the fingerprint");
        assertTrue(RecipientPicker.matches(DANA, "bbbbbbbb"));
        assertFalse(RecipientPicker.matches(DANA, "ada"));
        assertFalse(RecipientPicker.matches(key("F".repeat(40), "", '-', "E", false), "nobody"));
    }

    @Test
    void anUnusableKeySaysWhy() {
        assertNull(RecipientPicker.unusableText(DANA));
        assertEquals(tr("encryption.key.revoked"), RecipientPicker.unusableText(GONE));
        assertEquals(
                tr("encryption.key.expired"), RecipientPicker.unusableText(key("1".repeat(40), "x", 'e', "E", false)));
        String day = java.time.LocalDate.ofInstant(
                        java.time.Instant.ofEpochSecond(1_000_000_000L), java.time.ZoneId.systemDefault())
                .toString();
        assertEquals(
                tr("encryption.key.expiredOn", day),
                RecipientPicker.unusableText(key("2".repeat(40), "x", 'e', "E", false, 1_000_000_000L)),
                "an expiry date is named");
        assertEquals(
                tr("encryption.key.disabled"), RecipientPicker.unusableText(key("3".repeat(40), "x", 'd', "E", false)));
        assertEquals(
                tr("encryption.key.disabled"),
                RecipientPicker.unusableText(key("4".repeat(40), "x", '-', "ED", false)),
                "a disabled capability counts as disabled");
        assertEquals(
                tr("encryption.key.invalid"), RecipientPicker.unusableText(key("5".repeat(40), "x", 'i', "E", false)));
        assertEquals(
                tr("encryption.key.signOnly"), RecipientPicker.unusableText(key("6".repeat(40), "x", 'u', "S", true)));
    }

    @Test
    void theWarningNamesTheFirstUnverifiedKeyAndALockedOutOwner() {
        assertNull(RecipientPicker.warningFor(List.of(ME, DANA), true), "all verified, you included");
        assertNull(RecipientPicker.warningFor(List.of(), true), "nothing chosen, nothing to say");
        assertEquals(
                tr("encryption.picker.unverified", ZED.name(), ZED.groupedFingerprint()),
                RecipientPicker.warningFor(List.of(ME, ZED, BOB), true),
                "one unverified key at a time");
        assertEquals(tr("encryption.picker.withoutMe"), RecipientPicker.warningFor(List.of(DANA), true));
        assertNull(RecipientPicker.warningFor(List.of(DANA), false), "no secret key: nothing to lock out");
        assertEquals(
                tr("encryption.picker.unverified", BOB.name(), BOB.groupedFingerprint()) + "\n"
                        + tr("encryption.picker.withoutMe"),
                RecipientPicker.warningFor(List.of(BOB), true));
    }
}
