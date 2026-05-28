module com.editora {
    requires javafx.controls;
    requires javafx.fxml;

    opens com.editora to javafx.fxml;
    exports com.editora;
}
