module org.adriandeleon.editora {
    requires java.desktop;
    requires java.prefs;
    requires java.xml;
    requires javafx.controls;
    requires javafx.fxml;

    requires atlantafx.base;
    requires org.controlsfx.controls;
    requires org.kordamp.ikonli.bootstrapicons;
    requires org.fxmisc.flowless;
    requires org.fxmisc.richtext;
    requires org.kordamp.ikonli.javafx;
    requires reactfx;

    opens org.adriandeleon.editora to javafx.fxml;
    exports org.adriandeleon.editora.commands;
    exports org.adriandeleon.editora;
    exports org.adriandeleon.editora.plugins;

    uses org.adriandeleon.editora.plugins.EditoraPlugin;
}