package com.editora.editor.structured;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorFx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preview → PDF snapshots of the summary previews ({@link PreviewSnapshots}): a GitHub Actions workflow, a pom, a
 * crontab, an fstab, a systemd unit, an ssh_config, a Dockerfile and a Markwhen calendar each print as one image
 * of what their preview shows; text that does not parse, and a buffer whose preview is not snapshot-based, print
 * nothing (the caller falls back to the source).
 */
@Tag("fx")
class PreviewSnapshotsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static EditorBuffer buffer(String language, String text, Consumer<EditorBuffer> setUp) throws Exception {
        return EditorFx.callFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride(language);
            b.setContent(text);
            setUp.accept(b);
            b.getNode();
            return b;
        });
    }

    /** Runs the (multi-pulse) capture and waits for its result off the FX thread; null when it printed none. */
    private static PreviewSnapshots.Result capture(EditorBuffer b) throws Exception {
        CompletableFuture<Optional<PreviewSnapshots.Result>> done = new CompletableFuture<>();
        EditorFx.onFx(
                () -> PreviewSnapshots.capture(b, null, (at, of) -> {}, r -> done.complete(Optional.ofNullable(r))));
        return done.get(60, TimeUnit.SECONDS).orElse(null);
    }

    /** One decodable, non-empty PNG: what the summary previews print as. */
    private static void assertOneImage(String what, EditorBuffer b) throws Exception {
        PreviewSnapshots.Result r = capture(b);
        assertNotNull(r, what + " prints its preview");
        assertEquals(1, r.images().size(), what + " is one image");
        assertFalse(r.truncated(), what);
        BufferedImage img =
                ImageIO.read(new ByteArrayInputStream(r.images().get(0).png()));
        assertNotNull(img, what + " is a PNG");
        assertTrue(
                img.getWidth() > 100 && img.getHeight() > 20,
                what + " has content: " + img.getWidth() + "x" + img.getHeight());
    }

    @Test
    void eachSummaryPreviewPrintsAsOneImage() throws Exception {
        assertOneImage(
                "a GitHub Actions workflow",
                buffer(
                        "yaml",
                        "name: CI\non: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n"
                                + "      - uses: actions/checkout@v4\n      - run: mvn verify\n",
                        b -> b.setGithubActionsPreviewEnabled(true)));
        assertOneImage(
                "a pom",
                buffer(
                        "xml",
                        "<project><modelVersion>4.0.0</modelVersion><groupId>org.demo</groupId>"
                                + "<artifactId>demo</artifactId><version>1.0</version><dependencies><dependency>"
                                + "<groupId>org.junit</groupId><artifactId>junit</artifactId><version>5</version>"
                                + "</dependency></dependencies></project>\n",
                        b -> {
                            b.setPath(Path.of("pom.xml"));
                            b.setPomPreviewEnabled(true);
                        }));
        assertOneImage("a crontab", buffer("crontab", "*/5 * * * * /usr/local/bin/backup --quiet\n", b -> {}));
        assertOneImage(
                "an fstab",
                buffer(
                        "fstab",
                        "UUID=1234-abcd  /      ext4  defaults  0 1\ntmpfs  /tmp  tmpfs  noatime  0 0\n",
                        b -> {}));
        assertOneImage(
                "a systemd unit",
                buffer(
                        "systemd",
                        "[Unit]\nDescription=Demo service\n\n[Service]\nExecStart=/usr/bin/demo\n"
                                + "Restart=on-failure\n\n[Install]\nWantedBy=multi-user.target\n",
                        b -> {}));
        assertOneImage(
                "an ssh_config",
                buffer("ssh-config", "Host demo\n  HostName example.com\n  User adl\n  Port 2222\n", b -> {}));
        assertOneImage(
                "a Dockerfile",
                buffer(
                        "dockerfile",
                        "FROM eclipse-temurin:25 AS build\nRUN mvn -q package\n\nFROM alpine:3\n"
                                + "COPY --from=build /app/target/app.jar /app.jar\nCMD [\"java\", \"-jar\", \"/app.jar\"]\n",
                        b -> {}));
        assertOneImage(
                "a Markwhen calendar",
                buffer(
                        "markwhen",
                        "title: Plan\n\n2026-01-05: Kickoff #Plan\n2026-01-12/2026-01-23: Sprint one #Build\n",
                        b -> b.setMarkwhenView(EditorBuffer.MarkwhenView.CALENDAR)));
    }

    @Test
    void textThatDoesNotParseOrAPreviewThatIsNotASnapshotPrintsNothing() throws Exception {
        assertNull(
                capture(buffer("json", "{ \"open\": [1, 2", b -> b.setStructuredPreviewEnabled(true))), "broken JSON");
        assertNull(capture(buffer("xml", "<a><b></a>", b -> b.setStructuredPreviewEnabled(true))), "broken XML");
        assertNull(capture(buffer("plaintext", "just words\n", b -> {})), "plain text has no preview to print");
    }
}
