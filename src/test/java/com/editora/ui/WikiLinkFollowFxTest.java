package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.editor.EditorBuffer;
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
        List<String> log = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            EditorBuffer clicked = new EditorBuffer();
            clicked.setContent("[[note]]\n");
            clicked.setPath(doc);
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

            follow.accept("wikilink:note");
            assertEquals(List.of("open note.md"), log);
            log.clear();

            follow.accept("wikilink:note#Part two");
            assertEquals(List.of("jump note.md:4", "openThen note.md"), log);
            log.clear();

            follow.accept("wikilink:note#No such heading");
            assertEquals(
                    List.of("openThen note.md", "status " + tr("status.link.noAnchor", "#no-such-heading")),
                    log,
                    "the file still opens, and the missing heading is said");
            log.clear();

            follow.accept("wikilink:nowhere");
            assertEquals(List.of("status " + tr("status.wikilink.unresolved", "nowhere")), log);
            clicked.dispose();
        });
    }
}
