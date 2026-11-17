package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Following a wiki link's address: the resolved file is opened (at its heading), an unresolved one is said. */
@Tag("fx")
class WikiLinkFollowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theFileIsOpenedAtTheHeadingAndAMissingNoteIsReported(@TempDir Path dir) throws Exception {
        Path note = Files.writeString(dir.resolve("note.md"), "# Note\n\ntext\n\n## Part two\n\nmore\n");
        Path doc = Files.writeString(dir.resolve("doc.md"), "x");
        List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());
        EditorBuffer clicked = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("[[note]]\n");
            b.setPath(doc);
            return b;
        });
        java.util.function.Consumer<String> follow = address -> HeadingLinks.followWiki(
                address,
                clicked,
                file -> log.add("open " + file.getFileName()),
                (file, then) -> {
                    log.add("openThen " + file.getFileName());
                    then.run();
                },
                file -> null,
                (file, line, column) -> log.add("jump " + file.getFileName() + ":" + line),
                message -> log.add("status " + message));

        // The note is resolved and its heading found off the FX thread: each answer lands after the follow.
        assertEquals(List.of("open note.md"), followed(follow, "wikilink:note", log, 1));
        assertEquals(List.of("jump note.md:4", "openThen note.md"), followed(follow, "wikilink:note#Part two", log, 2));
        assertEquals(
                List.of("openThen note.md", "status " + tr("status.link.noAnchor", "#no-such-heading")),
                followed(follow, "wikilink:note#No such heading", log, 2),
                "the file still opens, and the missing heading is said");
        assertEquals(
                List.of("status " + tr("status.wikilink.unresolved", "nowhere")),
                followed(follow, "wikilink:nowhere", log, 1));
        FxTestSupport.runOnFx(clicked::dispose);
    }

    /** Follows {@code address} on the FX thread and waits for {@code entries} log lines. */
    private static List<String> followed(
            java.util.function.Consumer<String> follow, String address, List<String> log, int entries)
            throws Exception {
        log.clear();
        FxTestSupport.runOnFx(() -> follow.accept(address));
        Await.holdsWithin(() -> log.size() >= entries, Duration.ofSeconds(5));
        FxTestSupport.drainFx();
        synchronized (log) {
            return List.copyOf(log);
        }
    }
}
