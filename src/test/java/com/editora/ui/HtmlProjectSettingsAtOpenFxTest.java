package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import com.editora.config.Project;
import com.editora.lsp.FormattingIndent;
import com.editora.lsp.HtmlServerSettings;
import com.editora.lsp.LspManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTML review 3 A201: a real window learns its project after {@code init} configured its servers. Opening a page then
 * hands the HTML servers the project's {@code html} object, and says the keys it ignores — before, both waited for
 * some Settings change, so the project's pages were formatted with the user's settings.
 */
@Tag("fx")
class HtmlProjectSettingsAtOpenFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aProjectsHtmlSettingsApplyWhenItsWindowOpensAPage(@TempDir Path dir) throws Exception {
        Path site = Files.createDirectories(dir.resolve("site"));
        Files.createDirectories(site.resolve(".editora"));
        Files.writeString(
                site.resolve(".editora/settings.json"),
                "{\"html.format.wrapLineLength\": 30, \"html\": {\"format.wrapAtributes\": \"force\"}}");
        Path page = Files.writeString(site.resolve("wrap.html"), "<div class=\"one\" id=\"two\"><p>x</p></div>\n");
        FxWindowFixture fixture = FxWindowFixture.create(Files.createDirectories(dir.resolve("config")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setProjectSupport(true);
        });
        try (WindowRig w = new WindowRig(fixture)) {
            FxTestSupport.runOnFx(() ->
                    w.controller.setWindowContext(w.fx.windowManager, new Project("site", "site", site.toString())));
            w.async.awaitFx();

            w.open(page);

            String said = tr("status.html.projectKeysIgnored", "format.wrapAtributes");
            w.await("the ignored key said at open", () -> messages(w).contains(said));
            LspCoordinator coordinator = w.field("lspCoordinator");
            LspManager manager = FxTestSupport.field(coordinator, "lspManager");
            Function<Path, HtmlServerSettings> settings = FxTestSupport.field(manager, "htmlSettings");
            FxTestSupport.runOnFx(() -> assertEquals(
                    30,
                    settings.apply(site)
                            .formattingOptions(new FormattingIndent(4, true))
                            .getNumber("wrapLineLength")
                            .intValue(),
                    "the project's wrap column for its own root"));
        }
    }

    private static List<String> messages(WindowRig w) {
        StatusBar bar = FxTestSupport.field(w.controller, "statusBar");
        MessageLog log = FxTestSupport.field(bar, "messageLog");
        return log.entries().stream().map(MessageLog.Entry::text).toList();
    }
}
