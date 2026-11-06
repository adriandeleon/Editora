package com.editora.ui;

import java.util.List;

import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Mermaid, Diagrams (Graphviz and PlantUML) and Typst: the pages of the external tools that render a
 * preview, each with its switch, command fields and detection status. The pages' controls, their load and the probes
 * live here; {@link SettingsWindow} builds them with the other pages.
 */
final class SettingsRenderToolPages {

    private final SettingsWindow w;
    private final ConfigManager config;
    private final com.editora.mermaid.MermaidService mermaidService;
    private final com.editora.diagram.DiagramService diagramService;
    private final com.editora.typst.TypstService typstService;

    SettingsRenderToolPages(
            SettingsWindow w,
            ConfigManager config,
            com.editora.mermaid.MermaidService mermaidService,
            com.editora.diagram.DiagramService diagramService,
            com.editora.typst.TypstService typstService) {
        this.w = w;
        this.config = config;
        this.mermaidService = mermaidService;
        this.diagramService = diagramService;
        this.typstService = typstService;
    }

    @Initializer
    void buildControls() {
        mermaidCheck = new CheckBox(tr("settings.enableMermaid"));
        mermaidCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setMermaidSupport(now);
            w.apply();
            refreshMermaidStatus();
        });
        mmdcPathField = new TextField();
        mmdcPathField.setPromptText("mmdc");
        w.commitOnEnterOrBlur(mmdcPathField, () -> config.getSettings().getMmdcPath(), now -> {
            config.getSettings().setMmdcPath(now);
            w.apply();
            refreshMermaidStatus();
        });
        maidPathField = new TextField();
        maidPathField.setPromptText(com.editora.mermaid.MermaidService.DEFAULT_MAID);
        w.commitOnEnterOrBlur(maidPathField, () -> config.getSettings().getMaidPath(), now -> {
            config.getSettings().setMaidPath(now);
            w.apply();
            refreshMermaidStatus();
        });

        diagramCheck = new CheckBox(tr("settings.enableDiagram"));
        diagramCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setDiagramSupport(now);
            w.apply();
            refreshDiagramStatus();
        });
        dotPathField = new TextField();
        dotPathField.setPromptText("dot");
        w.commitOnEnterOrBlur(dotPathField, () -> config.getSettings().getDotPath(), now -> {
            config.getSettings().setDotPath(now);
            w.apply();
            refreshDiagramStatus();
        });
        plantumlPathField = new TextField();
        plantumlPathField.setPromptText("plantuml");
        w.commitOnEnterOrBlur(plantumlPathField, () -> config.getSettings().getPlantumlPath(), now -> {
            config.getSettings().setPlantumlPath(now);
            w.apply();
            refreshDiagramStatus();
        });

        typstCheck = new CheckBox(tr("settings.enableTypst"));
        typstCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setTypstSupport(now);
            w.apply();
            refreshTypstStatus();
        });
        typstPathField = new TextField();
        typstPathField.setPromptText("typst");
        w.commitOnEnterOrBlur(typstPathField, () -> config.getSettings().getTypstPath(), now -> {
            config.getSettings().setTypstPath(now);
            w.apply();
            refreshTypstStatus();
        });
    }

    private CheckBox mermaidCheck;

    private TextField mmdcPathField;

    private TextField maidPathField;
    private final ToolStatusRow mmdcStatus = new ToolStatusRow("mmdc");
    private final ToolStatusRow maidStatus = new ToolStatusRow("maid");
    private CheckBox diagramCheck;
    private TextField dotPathField;
    private TextField plantumlPathField;
    private CheckBox typstCheck;
    private TextField typstPathField;
    private final ToolStatusRow typstStatus = new ToolStatusRow("typst");
    private final ToolStatusRow dotStatus = new ToolStatusRow("dot");
    private final ToolStatusRow plantumlStatus = new ToolStatusRow("plantuml");

    VBox mermaidPage() {
        VBox p = w.page(tr("settings.cat.mermaid"), tr("settings.mermaid.subtitle"));
        w.dependsOn(p, mermaidCheck);
        Card c = w.card(p, null);
        w.checkRow(
                c,
                Category.MERMAID,
                mermaidCheck,
                tr("settings.mermaid.hint"),
                "mermaid diagram enable mmdc render mmd");
        w.installButtons.put(
                "mermaid", mmdcStatus.install(() -> w.installSupport("mermaid")).installButton());
        // Mermaid's detection is kept until a path changes (probing mmdc, or a maid command of the user's own,
        // takes seconds on the thread that lints .mmd files), so a tool installed meanwhile is found on request.
        mmdcStatus.link(tr("settings.mermaid.recheck"), () -> {
            if (mermaidService != null) {
                mermaidService.invalidate();
                refreshMermaidStatus();
            }
        });
        String m = tr("settings.mermaid.mmdcPath");
        w.toolRows(c, Category.MERMAID, m, null, mmdcPathField, mermaidCheck, mmdcStatus, "mermaid mmdc render");
        String maid = tr("settings.mermaid.maidPath");
        w.toolRows(
                c, Category.MERMAID, maid, null, maidPathField, mermaidCheck, maidStatus, "mermaid maid lint validate");
        return p;
    }

    /** LANGUAGES & TOOLS ▸ Diagrams: Graphviz DOT + PlantUML preview (external CLIs). */
    VBox diagramsPage() {
        VBox p = w.page(tr("settings.cat.diagrams"), tr("settings.diagrams.subtitle"));
        w.dependsOn(p, diagramCheck);
        Card c = w.card(p, null);
        w.checkRow(
                c,
                Category.DIAGRAMS,
                diagramCheck,
                tr("settings.diagram.hint"),
                "diagram dot graphviz plantuml enable render preview puml gv");
        // No installer for Graphviz or PlantUML: the docs say how (a package manager), instead of a button.
        Runnable docs = () -> {
            if (w.openUrl != null) {
                w.openUrl.accept(com.editora.AppInfo.HOMEPAGE + "/features/diagrams/");
            }
        };
        dotStatus.linkWhenMissing(tr("settings.diagram.howToInstall"), docs);
        plantumlStatus.linkWhenMissing(tr("settings.diagram.howToInstall"), docs);
        String dot = tr("settings.diagram.dotPath");
        w.toolRows(
                c, Category.DIAGRAMS, dot, null, dotPathField, diagramCheck, dotStatus, "diagram dot graphviz render");
        String puml = tr("settings.diagram.plantumlPath");
        w.toolRows(
                c,
                Category.DIAGRAMS,
                puml,
                null,
                plantumlPathField,
                diagramCheck,
                plantumlStatus,
                "diagram plantuml puml");
        return p;
    }

    private void refreshDiagramStatus() {
        if (diagramService == null) {
            return;
        }
        dotStatus.checking();
        plantumlStatus.checking();
        diagramService.detect(a -> {
            for (var kind : List.of(com.editora.diagram.DiagramKind.DOT, com.editora.diagram.DiagramKind.PLANTUML)) {
                boolean found = a.has(kind);
                List<String> argv = diagramService.command(kind);
                (kind == com.editora.diagram.DiagramKind.DOT ? dotStatus : plantumlStatus)
                        .show(found, found ? ToolStatusRow.pathOf(argv) : "", diagramCheck.isSelected());
            }
        });
    }

    /** LANGUAGES & TOOLS ▸ Typst: multi-page rendered document preview (the external typst CLI). */
    VBox typstPage() {
        VBox p = w.page(tr("settings.cat.typst"), tr("settings.typst.subtitle"));
        w.dependsOn(p, typstCheck);
        Card c = w.card(p, null);
        w.checkRow(
                c,
                Category.TYPST,
                typstCheck,
                tr("settings.typst.hint"),
                "typst document enable render preview typ pdf");
        w.installButtons.put(
                "typst", typstStatus.install(() -> w.installSupport("typst")).installButton());
        w.toolRows(
                c,
                Category.TYPST,
                tr("settings.typst.path"),
                null,
                typstPathField,
                typstCheck,
                typstStatus,
                "typst cli");
        return p;
    }

    private void refreshTypstStatus() {
        if (typstService == null) {
            return;
        }
        typstStatus.checking();
        typstService.detect(present -> typstStatus.show(
                present, present ? ToolStatusRow.pathOf(typstService.command()) : "", typstCheck.isSelected()));
    }

    void refreshMermaidStatus() {
        if (mermaidService == null || mermaidCheck == null) {
            return; // its page was never built: an install's refresh reached a Settings never shown (fourth review CF3)
        }
        mmdcStatus.checking();
        maidStatus.checking();
        mermaidService.detect(a -> {
            boolean on = mermaidCheck.isSelected();
            mmdcStatus.show(a.mmdc(), a.mmdc() ? ToolStatusRow.pathOf(mermaidService.mmdcCommand()) : "", on);
            // The default `npx -y @probelabs/maid` is found by its files, never by running npx (which would
            // fetch it from the npm registry): show where the installed package is.
            com.editora.mermaid.MaidLocator.Found install = a.maidInstall();
            maidStatus.show(
                    a.maid(),
                    install != null ? install.detail() : ToolStatusRow.pathOf(mermaidService.maidCommand()),
                    on);
        });
    }

    void load(Settings settings) {
        mermaidCheck.setSelected(settings.isMermaidSupport());
        mmdcPathField.setText(settings.getMmdcPath());
        maidPathField.setText(settings.getMaidPath());
        refreshMermaidStatus();
        diagramCheck.setSelected(settings.isDiagramSupport());
        dotPathField.setText(settings.getDotPath());
        plantumlPathField.setText(settings.getPlantumlPath());
        refreshDiagramStatus();
        typstCheck.setSelected(settings.isTypstSupport());
        typstPathField.setText(settings.getTypstPath());
        refreshTypstStatus();
    }
}
