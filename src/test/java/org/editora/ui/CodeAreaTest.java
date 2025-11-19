package org.editora.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

/**
 * Simple test to verify CodeArea selection works
 */
public class CodeAreaTest extends Application {
    
    @Override
    public void start(Stage primaryStage) {
        CodeArea codeArea = new CodeArea();
        codeArea.replaceText("Hello World\nThis is a test\nCan you select this text?");
        
        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(codeArea);
        
        Scene scene = new Scene(scrollPane, 600, 400);
        
        primaryStage.setTitle("CodeArea Selection Test");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        System.out.println("CodeArea editable: " + codeArea.isEditable());
        System.out.println("CodeArea disabled: " + codeArea.isDisabled());
        System.out.println("CodeArea focus traversable: " + codeArea.isFocusTraversable());
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}
