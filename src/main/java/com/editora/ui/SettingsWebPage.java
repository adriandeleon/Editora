package com.editora.ui;

import java.util.List;
import java.util.Map;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Web: HTML Live Preview with its browser choice (detected off the FX thread) and the HTTP client with its
 * response handler scripts, their Node.js and timeout. The page's controls, its load and the Node.js status live here.
 */
final class SettingsWebPage {

    private final SettingsWindow w;
    private final ConfigManager config;

    SettingsWebPage(SettingsWindow w, ConfigManager config) {
        this.w = w;
        this.config = config;
    }

    @Initializer
    void buildControls() {
        httpCheck = new CheckBox(tr("settings.httpClient.enable"));
        httpCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setHttpClientSupport(now);
            w.apply();
            refreshHttpNodeStatus();
        });
        httpScriptsCheck = new CheckBox(tr("settings.httpClient.scripts"));
        httpScriptsCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setHttpScriptsEnabled(now);
            w.apply();
            refreshHttpNodeStatus();
        });
        httpInlineResultsCheck = new CheckBox(tr("settings.httpClient.inlineResults"));
        httpInlineResultsCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setHttpInlineResults(now);
            w.apply();
        });
        httpNodeField = new TextField();
        httpNodeField.setPromptText("node");
        w.commitOnEnterOrBlur(httpNodeField, () -> config.getSettings().getHttpNodePath(), now -> {
            config.getSettings().setHttpNodePath(now);
            w.apply();
            refreshHttpNodeStatus();
        });
        httpScriptTimeoutSpinner = IntSpinners.editable(1, 600, 10, 1);
        httpScriptTimeoutSpinner.setPrefWidth(90);
        httpScriptTimeoutSpinner.setAccessibleText(tr("settings.httpClient.scriptTimeout"));
        httpScriptTimeoutSpinner.valueProperty().addListener((obs, was, now) -> {
            if (w.loading || now == null) {
                return;
            }
            config.getSettings().setHttpScriptTimeoutSeconds(now);
            w.apply();
        });

        htmlPreviewCheck = new CheckBox(tr("settings.htmlPreview.enable"));
        htmlPreviewCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setHtmlPreviewSupport(now);
            w.apply();
        });
    }

    private CheckBox httpCheck;
    private CheckBox httpScriptsCheck;
    private CheckBox httpInlineResultsCheck;
    private TextField httpNodeField;
    private Spinner<Integer> httpScriptTimeoutSpinner;
    private CheckBox htmlPreviewCheck;
    /** The browser HTML Live Preview opens (ids from {@link com.editora.web.Browsers}); filled off the FX thread. */
    private final ComboBox<String> htmlBrowserCombo = new ComboBox<>();

    private final Map<String, String> htmlBrowserNames = new java.util.HashMap<>();
    private boolean htmlBrowsersDetected; // until then a stored id is not "missing", just not looked up yet
    private final ToolStatusRow httpNodeStatus = new ToolStatusRow("node");

    /** LANGUAGES & TOOLS ▸ Web: HTML live preview + the built-in HTTP client (merged). */
    VBox build() {
        VBox p = w.page(tr("settings.cat.web"), tr("settings.web.subtitle"));
        Card preview = w.card(p, tr("settings.section.htmlPreview"));
        w.checkRow(
                preview,
                Category.WEB,
                htmlPreviewCheck,
                tr("settings.htmlPreview.hint"),
                "html live preview browser serve enable safari chrome firefox edge server localhost");
        htmlBrowserCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : browserComboLabel(id, htmlBrowserNames, htmlBrowsersDetected);
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        htmlBrowserCombo.valueProperty().addListener((o, was, now) -> {
            if (!w.loading && now != null) {
                config.getSettings().setHtmlPreviewBrowser(now);
                w.apply();
            }
        });
        w.controlRow(
                preview,
                Category.WEB,
                tr("settings.htmlPreview.browser"),
                null,
                SettingsWindow.dependent(htmlPreviewCheck, htmlBrowserCombo),
                "html live preview browser default chrome firefox safari edge open");
        Thread.ofVirtual().name("settings-browsers").start(() -> {
            List<com.editora.web.Browsers.Browser> found = com.editora.web.Browsers.detect(); // file probes
            javafx.application.Platform.runLater(() -> {
                found.forEach(b -> htmlBrowserNames.put(b.id(), HtmlPreviewCoordinator.label(b)));
                htmlBrowsersDetected = true;
                boolean prev = w.loading;
                w.loading = true;
                try {
                    htmlBrowserCombo
                            .getItems()
                            .setAll(found.stream().map(b -> b.id()).toList());
                    htmlBrowserCombo.setValue(null); // re-label a stored id that detection did not find
                    syncHtmlBrowser();
                } finally {
                    w.loading = prev;
                }
            });
        });
        Card http = w.card(p, tr("settings.section.httpClient"));
        w.checkRow(
                http,
                Category.WEB,
                httpCheck,
                tr("settings.httpClient.hint"),
                "http client rest request enable run send response built-in");
        w.checkRow(
                http,
                Category.WEB,
                httpInlineResultsCheck,
                tr("settings.httpClient.inlineResultsHint"),
                "http client result status inline request line gutter run outcome editor ghost");
        w.checkRow(
                http,
                Category.WEB,
                httpScriptsCheck,
                tr("settings.httpClient.scriptsHint"),
                "http client response handler javascript script node test assert client.global trust");
        w.toolRows(
                http,
                Category.WEB,
                tr("settings.httpClient.nodePath"),
                tr("settings.httpClient.nodePathHint"),
                httpNodeField,
                httpScriptsCheck,
                httpNodeStatus,
                "http client response handler node nodejs javascript");
        w.controlRow(
                http,
                Category.WEB,
                tr("settings.httpClient.scriptTimeout"),
                tr("settings.httpClient.scriptTimeoutHint"),
                SettingsWindow.dependent(httpScriptsCheck, new HBox(httpScriptTimeoutSpinner)),
                "http client response handler script timeout seconds node javascript");
        SettingsDependents.bind(httpCheck.selectedProperty(), httpScriptsCheck);
        return p;
    }

    /**
     * The "Open the preview in" label for a browser id: its detected name, else (once detection has run) the known
     * name marked as not found — a remembered browser that was uninstalled, never a raw id like {@code brave}.
     */
    static String browserComboLabel(String id, Map<String, String> detectedNames, boolean detected) {
        String name = detectedNames.get(id);
        if (name != null) {
            return name;
        }
        return detected ? tr("htmlPreview.browserMissing", com.editora.web.Browsers.knownName(id)) : id;
    }

    /** Shows the stored preview browser, or System Default when none was chosen yet. */
    private void syncHtmlBrowser() {
        String id = config.getSettings().getHtmlPreviewBrowser();
        htmlBrowserCombo.setValue(id.isBlank() ? com.editora.web.Browsers.SYSTEM_DEFAULT : id);
    }

    /** Whether the node the HTTP scripts run is there (a lookup, no process), like the Bash tool rows. */
    void refreshHttpNodeStatus() {
        // Blank: the shared Node.js setting, as the run uses it (review E6).
        String shown = com.editora.http.HttpNodeChoice.displayed(
                config.getSettings().getHttpNodePath(), config.getSettings().getNodePath());
        String found = ToolStatusRow.pathOf(com.editora.run.ProgramArgs.commandLine(shown));
        httpNodeStatus.show(!found.isEmpty(), found, httpCheck.isSelected() && httpScriptsCheck.isSelected());
    }

    void load(Settings settings) {
        httpCheck.setSelected(settings.isHttpClientSupport());
        httpScriptsCheck.setSelected(settings.isHttpScriptsEnabled());
        httpInlineResultsCheck.setSelected(settings.isHttpInlineResults());
        httpNodeField.setText(settings.getHttpNodePath());
        refreshHttpNodeStatus();
        syncHtmlBrowser();
        httpScriptTimeoutSpinner.getValueFactory().setValue(settings.getHttpScriptTimeoutSeconds());
        htmlPreviewCheck.setSelected(settings.isHtmlPreviewSupport());
    }

    void syncHtmlPreviewCheck() {
        boolean prev = w.loading;
        w.loading = true;
        try {
            htmlPreviewCheck.setSelected(config.getSettings().isHtmlPreviewSupport());
        } finally {
            w.loading = prev;
        }
    }
}
