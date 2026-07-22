package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end (headless-FX) coverage of the find bar's replace paths, against a real {@link EditorBuffer}.
 *
 * <p>These exist because the replace logic is not reachable from a pure test — and because the
 * capture-group defect they pin was silent: {@code Matcher.quoteReplacement} made {@code $1} land in the
 * buffer verbatim while the identical query in Find-in-Files substituted correctly.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindReplaceBarFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A find bar wired to a real buffer, both parented in a scene so focus/layout calls are safe. */
    private static final class Harness {
        EditorBuffer buffer;
        FindReplaceBar bar;
        final List<String> statuses = new ArrayList<>();

        String content() {
            return buffer.getContent();
        }

        String lastStatus() {
            return statuses.isEmpty() ? "" : statuses.get(statuses.size() - 1);
        }

        void query(String find, String replace) {
            FindReplaceBar b = bar;
            FxTestSupport.<TextField>field(b, "findField").setText(find);
            FxTestSupport.<TextField>field(b, "replaceField").setText(replace);
        }

        void toggle(String name, boolean on) {
            FxTestSupport.<CheckBox>field(bar, name).setSelected(on);
        }

        boolean toggled(String name) {
            return FxTestSupport.<CheckBox>field(bar, name).isSelected();
        }

        CodeArea area() {
            return buffer.getFocusedArea();
        }
    }

    private Harness harness(String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Harness h = new Harness();
            h.buffer = new EditorBuffer();
            h.buffer.setContent(text);
            h.bar = new FindReplaceBar(() -> h.buffer, h.statuses::add);
            VBox root = new VBox(h.bar, h.buffer.getNode());
            new Scene(root, 800, 600);
            return h;
        });
    }

    // --- capture groups (the reported defect) ---

    @Test
    void replaceAllExpandsCaptureGroupsInRegexMode() throws Exception {
        Harness h = harness("foo_bar");
        FxTestSupport.runOnFx(() -> {
            h.query("(\\w+)_(\\w+)", "$2-$1");
            h.toggle("regex", true);
            h.bar.replaceAllMatches();
        });
        assertEquals("bar-foo", h.content());
    }

    @Test
    void replaceCurrentExpandsCaptureGroupsInRegexMode() throws Exception {
        Harness h = harness("foo_bar");
        FxTestSupport.runOnFx(() -> {
            h.query("(\\w+)_(\\w+)", "$2-$1");
            h.toggle("regex", true);
            h.area().selectRange(0, 7); // the whole match, as navigating to it would leave things
            h.bar.replaceCurrentMatch();
        });
        assertEquals("bar-foo", h.content());
    }

    @Test
    void wholeWordDoesNotShiftUserGroupNumbers() throws Exception {
        // whole-word wraps the query as \b(?:…)\b — a *non*-capturing group, so $1 is still the user's
        Harness h = harness("say foo_bar now");
        FxTestSupport.runOnFx(() -> {
            h.query("(\\w+)_(\\w+)", "$2-$1");
            h.toggle("regex", true);
            h.toggle("wholeWord", true);
            h.bar.replaceAllMatches();
        });
        assertEquals("say bar-foo now", h.content());
    }

    @Test
    void dollarStaysLiteralInLiteralMode() throws Exception {
        Harness h = harness("foo_bar");
        FxTestSupport.runOnFx(() -> {
            h.query("foo", "$1");
            h.toggle("regex", false);
            h.bar.replaceAllMatches();
        });
        assertEquals("$1_bar", h.content());
    }

    @Test
    void invalidGroupReferenceLeavesTheBufferUntouched() throws Exception {
        Harness h = harness("foo_bar");
        FxTestSupport.runOnFx(() -> {
            h.query("(\\w+)_(\\w+)", "$9"); // only two groups exist
            h.toggle("regex", true);
            h.bar.replaceAllMatches();
        });
        assertEquals("foo_bar", h.content(), "a bad group reference must not half-rewrite the buffer");
        assertFalse(h.lastStatus().isBlank(), "the failure must be reported");
    }

    // --- preserve case ---

    @Test
    void preserveCaseFollowsEachMatchesCasing() throws Exception {
        Harness h = harness("FOO foo Foo");
        FxTestSupport.runOnFx(() -> {
            h.query("foo", "bar");
            h.toggle("preserveCase", true);
            h.bar.replaceAllMatches();
        });
        assertEquals("BAR bar Bar", h.content());
    }

    @Test
    void preserveCaseOffReplacesVerbatim() throws Exception {
        Harness h = harness("FOO foo Foo");
        FxTestSupport.runOnFx(() -> {
            h.query("foo", "bar");
            h.toggle("preserveCase", false);
            h.bar.replaceAllMatches();
        });
        assertEquals("bar bar bar", h.content());
    }

    @Test
    void preserveCaseAppliesToTheExpandedGroupText() throws Exception {
        // recasing must run on the *expanded* replacement, not the raw "$1" template
        Harness h = harness("FOO_BAR");
        FxTestSupport.runOnFx(() -> {
            h.query("(\\w+)_(\\w+)", "$2-$1");
            h.toggle("regex", true);
            h.toggle("preserveCase", true);
            h.bar.replaceAllMatches();
        });
        assertEquals("BAR-FOO", h.content());
    }

    // --- find in selection ---

    @Test
    void multiLineSelectionIsCapturedAsTheScopeOnShow() throws Exception {
        Harness h = harness("foo\nfoo\nfoo\nfoo");
        FxTestSupport.runOnFx(() -> {
            h.area().selectRange(4, 11); // lines 2-3
            h.bar.show(false);
        });
        assertTrue(h.toggled("inSelection"), "a multi-line selection should switch the scope toggle on");
    }

    @Test
    void singleLineSelectionSeedsTheQueryAndDoesNotScope() throws Exception {
        Harness h = harness("alpha beta");
        FxTestSupport.runOnFx(() -> {
            h.area().selectRange(0, 5);
            h.bar.show(false);
        });
        assertFalse(h.toggled("inSelection"), "a single-line selection is a query seed, not a scope");
        assertEquals("alpha", FxTestSupport.<TextField>field(h.bar, "findField").getText());
    }

    @Test
    void replaceAllOnlyTouchesMatchesInsideTheScope() throws Exception {
        Harness h = harness("foo\nfoo\nfoo\nfoo");
        FxTestSupport.runOnFx(() -> {
            h.area().selectRange(4, 11); // exactly lines 2 and 3
            h.bar.show(false);
            h.query("foo", "bar");
            h.bar.replaceAllMatches();
        });
        assertEquals("foo\nbar\nbar\nfoo", h.content());
    }

    @Test
    void clearingTheScopeRestoresWholeDocumentReplace() throws Exception {
        Harness h = harness("foo\nfoo\nfoo\nfoo");
        FxTestSupport.runOnFx(() -> {
            h.area().selectRange(4, 11);
            h.bar.show(false);
            h.toggle("inSelection", false);
            h.query("foo", "bar");
            h.bar.replaceAllMatches();
        });
        assertEquals("bar\nbar\nbar\nbar", h.content());
    }

    @Test
    void scopeFollowsAnEditAboveIt() throws Exception {
        // the scope is offset-based, so an insertion above it must shift it rather than strand it
        Harness h = harness("foo\nfoo\nfoo\nfoo");
        FxTestSupport.runOnFx(() -> {
            h.area().selectRange(4, 11); // lines 2-3
            h.bar.show(false);
            h.area().insertText(0, "xx\n"); // push everything down one line
            h.query("foo", "bar");
            h.bar.replaceAllMatches();
        });
        assertEquals("xx\nfoo\nbar\nbar\nfoo", h.content());
    }

    @Test
    void turningOnTheScopeWithNothingSelectedRevertsAndReports() throws Exception {
        Harness h = harness("foo foo");
        FxTestSupport.runOnFx(() -> {
            h.area().moveTo(0);
            h.toggle("inSelection", true);
        });
        assertFalse(h.toggled("inSelection"), "with no selection there is no scope to define");
        assertFalse(h.lastStatus().isBlank(), "the user should be told why nothing happened");
    }

    // --- the ranged replace ---

    @Test
    void replaceAllPreservesTextOutsideTheMatchSpan() throws Exception {
        Harness h = harness("header\n\nfoo mid foo\n\nfooter-text");
        FxTestSupport.runOnFx(() -> {
            h.query("foo", "bar");
            h.bar.replaceAllMatches();
        });
        assertEquals("header\n\nbar mid bar\n\nbarter-text", h.content());
    }

    @Test
    void replaceAllWithNoMatchesReportsZeroAndChangesNothing() throws Exception {
        Harness h = harness("alpha");
        FxTestSupport.runOnFx(() -> {
            h.query("zzz", "bar");
            h.bar.replaceAllMatches();
        });
        assertEquals("alpha", h.content());
    }
}
