package com.editora.ui;

import java.util.Map;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import com.editora.AppInfo;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Small pieces of the Settings window's frame: where each page's "?" help link goes, and the sidebar's badges
 * (how many rows of a page differ from the default, and the Beta pill).
 */
final class SettingsFrameBits {

    private SettingsFrameBits() {}

    /** The docs page for each Settings page (by its category name); a page without one links the docs home. */
    private static final Map<String, String> DOCS = Map.ofEntries(
            Map.entry("APPEARANCE", "themes-fonts"),
            Map.entry("INTERFACE", "workspace"),
            Map.entry("TOOLBAR", "workspace"),
            Map.entry("WORKSPACE", "workspace"),
            Map.entry("TOOL_WINDOWS", "workspace"),
            Map.entry("EDITOR", "editing"),
            Map.entry("COMPLETION", "editing"),
            Map.entry("SNIPPETS", "snippets-templates"),
            Map.entry("TEMPLATES", "snippets-templates"),
            Map.entry("TODO", "todo"),
            Map.entry("SPELL_CHECK", "editing"),
            Map.entry("SEARCH", "navigation"),
            Map.entry("ABBREVIATIONS", "snippets-templates"),
            Map.entry("AI_GENERAL", "ai"),
            Map.entry("AGENT", "ai"),
            Map.entry("AI", "ai"),
            Map.entry("LSP", "lsp"),
            Map.entry("DEBUG", "run-debug"),
            Map.entry("MARKDOWN", "markdown"),
            Map.entry("MERMAID", "diagrams"),
            Map.entry("DIAGRAMS", "diagrams"),
            Map.entry("TYPST", "typst"),
            Map.entry("BUILD_TOOLS", "build-tools"),
            Map.entry("WEB", "http-client"),
            Map.entry("EXTERNAL_TOOLS", "external-tools"),
            Map.entry("GIT", "git"),
            Map.entry("GITHUB", "github"),
            Map.entry("KEYMAPS", "keymaps"),
            Map.entry("MACROS", "macros"),
            Map.entry("REMOTE", "remote"),
            Map.entry("PLUGINS", "plugins"),
            Map.entry("SYNC", "settings-sync"),
            Map.entry("MCP", "mcp"),
            Map.entry("ADVANCED", "configuration"));

    /**
     * The help page for the Settings page {@code category} (an enum constant name): this build's versioned docs
     * ({@link AppInfo#docsUrl()}, as the Help menu and the palette's per-command help use), so the page matches the
     * version that is running.
     */
    static String helpUrl(String category) {
        String page = DOCS.get(category);
        return AppInfo.docsUrl() + (page == null ? "" : "/" + page);
    }

    /** The sidebar's badges for a page: its modified-row count (none at 0) and the Beta pill; null for neither. */
    static @Nullable Node sidebarBadges(boolean beta, int modified) {
        if (!beta && modified <= 0) {
            return null;
        }
        HBox box = new HBox(4);
        box.setAlignment(Pos.CENTER_LEFT);
        if (modified > 0) {
            Label count = new Label(Integer.toString(modified));
            count.getStyleClass().add("settings-modified-count");
            count.setAccessibleText(tr("settings.page.modifiedCount", modified));
            box.getChildren().add(count);
        }
        if (beta) {
            Label pill = new Label(tr("settings.beta"));
            pill.getStyleClass().add("settings-beta-pill");
            box.getChildren().add(pill);
        }
        return box;
    }
}
