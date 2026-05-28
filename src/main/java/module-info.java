module com.editora {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.fxmisc.richtext;
    requires org.fxmisc.flowless;
    requires reactfx;
    requires atlantafx.base;
    requires com.fasterxml.jackson.databind;

    opens com.editora to javafx.fxml;
    opens com.editora.ui to javafx.fxml;
    opens com.editora.config to com.fasterxml.jackson.databind;

    exports com.editora;
    exports com.editora.command;
    exports com.editora.config;
    exports com.editora.editor;
    exports com.editora.ui;
}
