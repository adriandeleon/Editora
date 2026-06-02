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
    // Apache Lucene's pure-Java Hunspell spell checker (analysis.common) + its store/FST (core).
    requires org.apache.lucene.analysis.common;
    requires org.apache.lucene.core;
    // Lets Lucene read JVM internals for its optimizations (silences a startup warning); jlink bundles it.
    requires jdk.management;
    requires org.commonmark;
    requires org.commonmark.ext.gfm.tables;
    requires org.commonmark.ext.gfm.strikethrough;
    requires org.commonmark.ext.task.list.items;
    requires org.commonmark.ext.autolink;
    requires java.logging;

    opens com.editora to javafx.fxml;
    opens com.editora.ui to javafx.fxml;
    opens com.editora.config to com.fasterxml.jackson.databind;
    // Jackson reflects on the snippet JSON DTO (SnippetManager.Dto). The bundled snippet *resources*
    // need no opens — our own SnippetManager reads them via Class.getResourceAsStream.
    opens com.editora.snippet to com.fasterxml.jackson.databind;
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
