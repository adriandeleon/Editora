package com.editora.ui;

import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Project Map's Overview card (review 3 B4, E6): what a stranger to the project wants first — its README, build
 * systems and modules, languages, how the top-level folders divide the files, likely entry points, and where the
 * tests and docs are — with every folder and file one press from the map. It floats over the map like the code and
 * note cards ({@link MapCards}), opens by itself on a project's first Map open and from ⋯ → Project overview.
 */
final class ProjectMapOverviewCard extends StackPane {

    static final double DEFAULT_WIDTH = 440;
    static final double DEFAULT_HEIGHT = 520;
    static final double MIN_WIDTH = 320;
    static final double MIN_HEIGHT = 220;

    private final BorderPane frame = new BorderPane();
    private final HBox titleBar = new HBox(7);
    private final Label title = new Label();
    private final VBox sections = new VBox(10);
    private final ScrollPane scroll = new ScrollPane(sections);
    private final Button close = new Button("×");
    private final Region resizeGrip = new Region();
    private final Scale mapZoom = new Scale(1, 1, 0, 0);

    private Runnable onClose = () -> setVisible(false);
    private Runnable onActivate = () -> {};
    private Runnable onEscape = () -> onClose.run();
    private Consumer<Path> onReveal = path -> {};
    private Consumer<Path> onOpen = path -> {};
    private @Nullable Path root;

    private boolean placed;
    private double preferredWidth = DEFAULT_WIDTH;
    private double preferredHeight = DEFAULT_HEIGHT;
    private double dragScreenX;
    private double dragScreenY;
    private double dragLayoutX;
    private double dragLayoutY;
    private double resizeScreenX;
    private double resizeScreenY;
    private double resizeWidth;
    private double resizeHeight;

    ProjectMapOverviewCard() {
        getStyleClass().addAll("project-map-preview", "project-map-overview-card");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setManaged(false);
        setVisible(false);
        title.getStyleClass().add("project-map-preview-title");
        title.setMinWidth(0);
        title.setMnemonicParsing(false);
        title.setGraphic(Icons.fitView());
        HBox.setHgrow(title, Priority.ALWAYS);
        close.getStyleClass().add("project-map-preview-button");
        close.setTooltip(new Tooltip(tr("project.map.preview.close")));
        close.setAccessibleText(tr("project.map.preview.close"));
        close.setOnAction(event -> onClose.run());
        titleBar.getStyleClass().addAll("project-map-preview-header", "project-map-overview-header");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getChildren().setAll(title, close);
        titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, this::dragPressed);
        titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);
        sections.setPadding(new Insets(10, 12, 12, 12));
        sections.getStyleClass().add("project-map-overview-sections");
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("project-map-overview-scroll");
        frame.getStyleClass().add("project-map-preview-frame");
        frame.setTop(titleBar);
        frame.setCenter(scroll);
        resizeGrip.getStyleClass().add("project-map-preview-resize");
        resizeGrip.setCursor(Cursor.SE_RESIZE);
        resizeGrip.addEventHandler(MouseEvent.MOUSE_PRESSED, this::resizePressed);
        resizeGrip.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::resized);
        getChildren().addAll(frame, resizeGrip);
        getTransforms().add(mapZoom);
        addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            toFront();
            onActivate.run();
        });
        focusWithinProperty().addListener((obs, was, within) -> {
            if (within) {
                toFront();
                onActivate.run();
            }
        });
        addEventFilter(ScrollEvent.SCROLL, event -> onActivate.run());
        getProperties().put(com.editora.command.KeyDispatcher.CLAIMED_KEYS, ProjectMapPreview.GEOMETRY_KEYS);
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            double[] step = ProjectMapPreview.geometryStep(event);
            if (step != null) {
                moveOrResizeBy(step);
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.ESCAPE
                    && !event.isShiftDown()
                    && !event.isControlDown()
                    && !event.isAltDown()
                    && !event.isMetaDown()) {
                onEscape.run();
                event.consume();
            }
        });
    }

    /** Fills the card with {@code facts} of the project at {@code root}. */
    void show(Path root, ProjectMapOverview.Facts facts) {
        this.root = root;
        String name = root.getFileName() == null
                ? root.toString()
                : root.getFileName().toString();
        title.setText(tr("project.map.overview.title", name));
        setAccessibleText(tr("project.map.overview.title", name));
        List<Node> content = new ArrayList<>();
        content.add(text(tr("project.map.overview.hint"), "project-map-overview-hint"));
        readme(content, facts);
        builds(content, facts);
        languages(content, facts);
        topFolders(content, facts);
        paths(content, "project.map.overview.entryPoints", facts.entryPoints(), facts.moreEntryPoints(), false);
        paths(content, "project.map.overview.tests", facts.testFolders(), 0, true);
        paths(content, "project.map.overview.docs", facts.docFolders(), 0, true);
        String total = tr("project.map.overview.total", count(facts.files()), count(facts.folders()));
        content.add(text(
                facts.truncated() ? total + " " + tr("project.map.overview.truncated") : total,
                "project-map-overview-footer"));
        sections.getChildren().setAll(content);
    }

    /** Places the card and shows it. */
    void place(ProjectMapPreview.Placement placement) {
        setVisible(true);
        resizeRelocate(placement.x(), placement.y(), placement.width(), placement.height());
        preferredWidth = placement.width();
        preferredHeight = placement.height();
        placed = true;
        toFront();
    }

    String title() {
        return title.getText();
    }

    /** The section headings shown, in order (for tests). */
    List<String> headings() {
        return sections.getChildren().stream()
                .filter(node -> node.getStyleClass().contains("project-map-overview-heading"))
                .map(node -> ((Label) node).getText())
                .toList();
    }

    /** The texts of the card's link buttons, in order (for tests). */
    List<String> links() {
        List<String> links = new ArrayList<>();
        collectLinks(sections, links);
        return links;
    }

    private static void collectLinks(javafx.scene.Parent parent, List<String> links) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Button button) {
                links.add(button.getText());
            } else if (child instanceof javafx.scene.Parent inner) {
                collectLinks(inner, links);
            }
        }
    }

    void setOnClose(Runnable action) {
        onClose = action == null ? () -> setVisible(false) : action;
    }

    void setOnActivate(Runnable action) {
        onActivate = action == null ? () -> {} : action;
    }

    void setOnEscape(Runnable action) {
        onEscape = action == null ? () -> onClose.run() : action;
    }

    /** What a press on a folder or file of the card does: the map selects and reveals it. */
    void setOnReveal(Consumer<Path> action) {
        onReveal = action == null ? path -> {} : action;
    }

    /** What the README's Open button does: the file opens in the editor. */
    void setOnOpen(Consumer<Path> action) {
        onOpen = action == null ? path -> {} : action;
    }

    /** Moves the keyboard into the card: its first button. */
    void focusContent() {
        Button first = firstButton(sections);
        (first == null ? close : first).requestFocus();
    }

    private static @Nullable Button firstButton(javafx.scene.Parent parent) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Button button) {
                return button;
            }
            if (child instanceof javafx.scene.Parent inner) {
                Button found = firstButton(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    void constrainTo(double width, double height) {
        if (!placed || width <= 0 || height <= 0) {
            return;
        }
        resize(
                ProjectMapPreview.boundedSize(
                        preferredWidth, MIN_WIDTH, ProjectMapPreview.unzoomedRoom(width, mapScale())),
                ProjectMapPreview.boundedSize(
                        preferredHeight, MIN_HEIGHT, ProjectMapPreview.unzoomedRoom(height, mapScale())));
    }

    void setMapScale(double scale) {
        mapZoom.setX(scale);
        mapZoom.setY(scale);
    }

    double mapScale() {
        return mapZoom.getX();
    }

    @Override
    protected void layoutChildren() {
        frame.resizeRelocate(0, 0, getWidth(), getHeight());
        double grip = 18;
        resizeGrip.resizeRelocate(Math.max(0, getWidth() - grip), Math.max(0, getHeight() - grip), grip, grip);
    }

    // --- sections -------------------------------------------------------------------------------------------

    private void readme(List<Node> content, ProjectMapOverview.Facts facts) {
        content.add(heading(tr("project.map.overview.readme")));
        String readme = facts.readme();
        Path base = root;
        if (readme == null || base == null) {
            content.add(text(tr("project.map.overview.noReadme"), "project-map-overview-muted"));
            return;
        }
        if (!facts.readmeExcerpt().isBlank()) {
            content.add(text(facts.readmeExcerpt(), "project-map-overview-readme"));
        }
        Path file = base.resolve(readme);
        Button open = new Button(tr("project.map.overview.openReadme", readme));
        open.setMnemonicParsing(false);
        open.getStyleClass().addAll("flat", "project-map-overview-open");
        open.setOnAction(event -> onOpen.accept(file));
        content.add(open);
    }

    private void builds(List<Node> content, ProjectMapOverview.Facts facts) {
        content.add(heading(tr("project.map.overview.build")));
        if (facts.builds().isEmpty()) {
            content.add(text(tr("project.map.overview.noBuild"), "project-map-overview-muted"));
            return;
        }
        for (ProjectMapOverview.Build build : facts.builds()) {
            content.add(text(
                    tr("project.map.overview.buildLine", build.system(), build.marker(), build.folders()),
                    "project-map-overview-text"));
        }
        List<String> folders = new ArrayList<>();
        for (ProjectMapOverview.Module module : facts.modules()) {
            if (!module.folder().isEmpty()) {
                folders.add(module.folder());
            }
        }
        if (!folders.isEmpty()) {
            content.add(text(tr("project.map.overview.modules"), "project-map-overview-muted"));
            content.add(links(folders.subList(0, Math.min(ProjectMapOverview.MAX_LISTED, folders.size())), true));
            if (folders.size() > ProjectMapOverview.MAX_LISTED) {
                content.add(text(
                        tr("project.map.overview.more", count(folders.size() - ProjectMapOverview.MAX_LISTED)),
                        "project-map-overview-muted"));
            }
        }
    }

    private void languages(List<Node> content, ProjectMapOverview.Facts facts) {
        if (facts.languages().isEmpty()) {
            return;
        }
        content.add(heading(tr("project.map.overview.languages")));
        int total = facts.languages().stream()
                .mapToInt(ProjectMapOverview.Language::files)
                .sum();
        List<String> parts = new ArrayList<>();
        for (ProjectMapOverview.Language language : facts.languages()) {
            long percent = Math.round(100.0 * language.files() / Math.max(1, total));
            parts.add(tr("project.map.overview.language", language.name(), percent == 0 ? "<1" : percent));
        }
        content.add(text(String.join(" · ", parts), "project-map-overview-text"));
    }

    private void topFolders(List<Node> content, ProjectMapOverview.Facts facts) {
        if (facts.topFolders().isEmpty()) {
            return;
        }
        content.add(heading(tr("project.map.overview.folders")));
        Path base = root;
        VBox rows = new VBox(1);
        for (ProjectMapOverview.Folder folder : facts.topFolders()) {
            Button link = link(folder.name() + "/", base == null ? null : base.resolve(folder.name()));
            String detail = folder.language() == null
                    ? tr("project.map.overview.files", count(folder.files()))
                    : tr("project.map.overview.filesIn", count(folder.files()), folder.language());
            Label meta = text(detail, "project-map-overview-muted");
            HBox row = new HBox(8, link, meta);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }
        content.add(rows);
    }

    private void paths(List<Node> content, String headingKey, List<String> paths, int more, boolean folders) {
        if (paths.isEmpty()) {
            return;
        }
        content.add(heading(tr(headingKey)));
        content.add(links(paths, folders));
        if (more > 0) {
            content.add(text(tr("project.map.overview.more", count(more)), "project-map-overview-muted"));
        }
    }

    private FlowPane links(List<String> paths, boolean folders) {
        FlowPane flow = new FlowPane(4, 2);
        Path base = root;
        for (String rel : paths) {
            flow.getChildren().add(link(folders ? rel + "/" : rel, base == null ? null : base.resolve(rel)));
        }
        return flow;
    }

    private Button link(String label, @Nullable Path target) {
        Button link = new Button(label);
        link.setMnemonicParsing(false); // snake_case names keep their first underscore (review 3 E4)
        link.getStyleClass().addAll("flat", "project-map-overview-link");
        link.setTooltip(new Tooltip(tr("project.map.overview.reveal")));
        if (target != null) {
            link.setOnAction(event -> onReveal.accept(target));
        }
        return link;
    }

    private static Label heading(String value) {
        Label heading = new Label(value);
        heading.setMnemonicParsing(false);
        heading.getStyleClass().add("project-map-overview-heading");
        return heading;
    }

    private static Label text(String value, String styleClass) {
        Label label = new Label(value);
        label.setMnemonicParsing(false);
        label.setWrapText(true);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String count(int value) {
        return NumberFormat.getIntegerInstance(FileHistoryPanel.locale()).format(value);
    }

    // --- moving and sizing (as the note card) ----------------------------------------------------------------

    private void moveOrResizeBy(double[] step) {
        double scale = mapScale();
        if (step[0] != 0 || step[1] != 0) {
            relocate(getLayoutX() + step[0], getLayoutY() + step[1]);
        } else if (getParent() instanceof Region parent) {
            preferredWidth = ProjectMapPreview.boundedSize(
                    getWidth() + step[2] / scale,
                    MIN_WIDTH,
                    Math.max(1, ProjectMapPreview.unzoomedRoom(parent.getWidth(), scale)));
            preferredHeight = ProjectMapPreview.boundedSize(
                    getHeight() + step[3] / scale,
                    MIN_HEIGHT,
                    Math.max(1, ProjectMapPreview.unzoomedRoom(parent.getHeight(), scale)));
            resize(preferredWidth, preferredHeight);
        }
        ProjectMapPreview.announceGeometry(this, scale);
    }

    private void dragPressed(MouseEvent event) {
        if (event.getTarget() instanceof Button || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        dragScreenX = event.getScreenX();
        dragScreenY = event.getScreenY();
        dragLayoutX = getLayoutX();
        dragLayoutY = getLayoutY();
        event.consume();
    }

    private void dragged(MouseEvent event) {
        if (!event.isPrimaryButtonDown()) {
            return;
        }
        relocate(dragLayoutX + event.getScreenX() - dragScreenX, dragLayoutY + event.getScreenY() - dragScreenY);
        event.consume();
    }

    private void resizePressed(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        resizeScreenX = event.getScreenX();
        resizeScreenY = event.getScreenY();
        resizeWidth = getWidth();
        resizeHeight = getHeight();
        event.consume();
    }

    private void resized(MouseEvent event) {
        if (!(getParent() instanceof Region parent) || !event.isPrimaryButtonDown()) {
            return;
        }
        double scale = mapScale();
        preferredWidth = ProjectMapPreview.boundedSize(
                resizeWidth + (event.getScreenX() - resizeScreenX) / scale,
                MIN_WIDTH,
                Math.max(1, ProjectMapPreview.unzoomedRoom(parent.getWidth(), scale)));
        preferredHeight = ProjectMapPreview.boundedSize(
                resizeHeight + (event.getScreenY() - resizeScreenY) / scale,
                MIN_HEIGHT,
                Math.max(1, ProjectMapPreview.unzoomedRoom(parent.getHeight(), scale)));
        resize(preferredWidth, preferredHeight);
        event.consume();
    }
}
