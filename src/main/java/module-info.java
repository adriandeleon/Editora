module com.editora {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.fxmisc.richtext;
    requires org.fxmisc.flowless;
    requires org.fxmisc.undo;
    requires reactfx;
    requires atlantafx.base;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.toml;
    requires org.eclipse.tm4e.core;
    requires org.commonmark;
    requires org.commonmark.ext.gfm.tables;
    requires org.commonmark.ext.gfm.strikethrough;
    requires org.commonmark.ext.task.list.items;
    requires org.commonmark.ext.autolink;
    requires java.logging;

    opens com.editora to javafx.fxml;
    opens com.editora.ui to javafx.fxml;
    opens com.editora.config to com.fasterxml.jackson.databind;
    // tm4e (a separate module) reads these grammar resources via Class.getResourceAsStream;
    // without opening the package, JPMS encapsulates the .tmLanguage.json files and grammar
    // loading silently fails, falling back to the legacy regex highlighter.
    opens com.editora.grammars to org.eclipse.tm4e.core;

    exports com.editora;
    exports com.editora.command;
    exports com.editora.config;
    exports com.editora.editor;
    exports com.editora.ui;
}
