package com.editora.editor;

import java.util.Locale;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.editora.i18n.Messages;
import com.editora.structured.OpenApiModel;

/**
 * Renders an {@link OpenApiModel} into browsable, Redoc-style API docs for the 3-mode preview: a title
 * header, servers, endpoints grouped by path (colored method badges + params + responses), and schemas.
 * Self-scrolling (wrapped in a {@code ScrollPane}) so it hosts directly as the Split/Preview side, like
 * {@link StructuredTree}. Kept in {@code editor} (no {@code ui} dependency).
 */
public final class OpenApiDoc {

    private OpenApiDoc() {}

    public static Node build(OpenApiModel m) {
        VBox root = new VBox();
        root.getStyleClass().add("openapi-doc");

        String title = m.title() == null || m.title().isBlank() ? Messages.tr("openapi.untitled") : m.title();
        Label h = new Label(m.version() == null || m.version().isBlank() ? title : title + "  " + m.version());
        h.getStyleClass().add("openapi-title");
        h.setWrapText(true);
        root.getChildren().add(h);
        if (m.description() != null && !m.description().isBlank()) {
            root.getChildren().add(wrapped(m.description(), "openapi-desc"));
        }

        if (!m.servers().isEmpty()) {
            root.getChildren().add(section(Messages.tr("openapi.servers")));
            for (OpenApiModel.Server s : m.servers()) {
                root.getChildren().add(wrapped(s.url(), "openapi-server"));
            }
        }

        if (!m.paths().isEmpty()) {
            root.getChildren().add(section(Messages.tr("openapi.endpoints")));
            for (OpenApiModel.PathItem p : m.paths()) {
                for (OpenApiModel.Operation op : p.operations()) {
                    root.getChildren().add(operationNode(p.path(), op));
                }
            }
        }

        if (!m.schemas().isEmpty()) {
            root.getChildren().add(section(Messages.tr("openapi.schemas")));
            for (OpenApiModel.Schema s : m.schemas()) {
                root.getChildren().add(schemaNode(s));
            }
        }

        ScrollPane sp = new ScrollPane(root);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("openapi-scroll");
        return sp;
    }

    private static Node operationNode(String path, OpenApiModel.Operation op) {
        VBox box = new VBox();
        box.getStyleClass().add("openapi-op");

        Label badge = new Label(op.method());
        badge.getStyleClass()
                .addAll("openapi-method", "openapi-method-" + op.method().toLowerCase(Locale.ROOT));
        Label pathLabel = new Label(path);
        pathLabel.getStyleClass().add("openapi-path");
        HBox head = new HBox(8, badge, pathLabel);
        head.setAlignment(Pos.CENTER_LEFT);
        if (op.deprecated()) {
            Label dep = new Label(Messages.tr("openapi.deprecated"));
            dep.getStyleClass().add("openapi-deprecated");
            head.getChildren().add(dep);
        }
        box.getChildren().add(head);
        if (op.summary() != null && !op.summary().isBlank()) {
            box.getChildren().add(wrapped(op.summary(), "openapi-summary"));
        }

        if (!op.parameters().isEmpty()) {
            box.getChildren().add(subhead(Messages.tr("openapi.parameters")));
            for (OpenApiModel.Param p : op.parameters()) {
                StringBuilder sb = new StringBuilder(p.name());
                if (p.in() != null) {
                    sb.append(" (").append(p.in()).append(')');
                }
                if (p.type() != null && !p.type().isBlank()) {
                    sb.append(" : ").append(p.type());
                }
                if (p.required()) {
                    sb.append("  ").append(Messages.tr("openapi.required"));
                }
                box.getChildren().add(detail(sb.toString()));
            }
        }
        if (!op.responses().isEmpty()) {
            box.getChildren().add(subhead(Messages.tr("openapi.responses")));
            for (OpenApiModel.Response r : op.responses()) {
                String d = r.description() == null || r.description().isBlank() ? "" : " — " + r.description();
                box.getChildren().add(detail(r.code() + d));
            }
        }
        return box;
    }

    private static Node schemaNode(OpenApiModel.Schema s) {
        VBox box = new VBox();
        box.getStyleClass().add("openapi-schema");
        String type = s.type() == null || s.type().isBlank() ? "" : "  : " + s.type();
        Label name = new Label(s.name() + type);
        name.getStyleClass().add("openapi-schema-name");
        box.getChildren().add(name);
        for (OpenApiModel.Property p : s.properties()) {
            String req = p.required() ? "  " + Messages.tr("openapi.required") : "";
            box.getChildren().add(detail(p.name() + " : " + p.type() + req));
        }
        return box;
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("openapi-section");
        return l;
    }

    private static Label subhead(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("openapi-subhead");
        return l;
    }

    private static Label detail(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("openapi-detail");
        l.setWrapText(true);
        return l;
    }

    private static Label wrapped(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }
}
