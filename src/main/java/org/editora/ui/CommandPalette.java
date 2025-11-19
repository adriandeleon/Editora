package org.editora.ui;

import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Modality;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.geometry.Insets;

import java.util.ArrayList;
import java.util.List;

/**
 * Command palette for quick access to editor commands
 */
public class CommandPalette extends Stage {
    
    private final TextField searchField;
    private final ListView<Command> commandList;
    private final ObservableList<Command> allCommands;
    private final ObservableList<Command> filteredCommands;
    
    public CommandPalette(Stage owner) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Command Palette");
        setWidth(600);
        setHeight(400);
        
        allCommands = FXCollections.observableArrayList();
        filteredCommands = FXCollections.observableArrayList();
        
        // Search field
        searchField = new TextField();
        searchField.setPromptText("Type a command...");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterCommands(newVal));
        
        // Command list
        commandList = new ListView<>(filteredCommands);
        commandList.setCellFactory(lv -> new CommandCell());
        commandList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                executeSelectedCommand();
            }
        });
        
        // Layout
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        root.getChildren().addAll(searchField, commandList);
        
        Scene scene = new Scene(root);
        setScene(scene);
        
        // Keyboard shortcuts
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                close();
            } else if (event.getCode() == KeyCode.ENTER) {
                executeSelectedCommand();
            } else if (event.getCode() == KeyCode.DOWN) {
                commandList.getSelectionModel().selectNext();
                event.consume();
            } else if (event.getCode() == KeyCode.UP) {
                commandList.getSelectionModel().selectPrevious();
                event.consume();
            }
        });
        
        // Focus search field when shown
        setOnShowing(event -> {
            searchField.clear();
            searchField.requestFocus();
            filterCommands("");
        });
    }
    
    public void addCommand(String name, String description, Runnable action) {
        allCommands.add(new Command(name, description, action));
    }
    
    public int getCommandCount() {
        return allCommands.size();
    }
    
    private void filterCommands(String query) {
        filteredCommands.clear();
        
        if (query == null || query.isEmpty()) {
            filteredCommands.addAll(allCommands);
        } else {
            String lowerQuery = query.toLowerCase();
            for (Command cmd : allCommands) {
                if (cmd.name.toLowerCase().contains(lowerQuery) || 
                    cmd.description.toLowerCase().contains(lowerQuery)) {
                    filteredCommands.add(cmd);
                }
            }
        }
        
        // Select first item if available
        if (!filteredCommands.isEmpty()) {
            commandList.getSelectionModel().selectFirst();
        }
    }
    
    private void executeSelectedCommand() {
        Command selected = commandList.getSelectionModel().getSelectedItem();
        if (selected != null && selected.action != null) {
            close();
            selected.action.run();
        }
    }
    
    /**
     * Command data class
     */
    public static class Command {
        private final String name;
        private final String description;
        private final Runnable action;
        
        public Command(String name, String description, Runnable action) {
            this.name = name;
            this.description = description;
            this.action = action;
        }
        
        public String getName() {
            return name;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * Custom cell for displaying commands
     */
    private static class CommandCell extends ListCell<Command> {
        @Override
        protected void updateItem(Command command, boolean empty) {
            super.updateItem(command, empty);
            
            if (empty || command == null) {
                setText(null);
                setGraphic(null);
            } else {
                VBox content = new VBox(2);
                Label nameLabel = new Label(command.name);
                nameLabel.setStyle("-fx-font-weight: bold;");
                
                Label descLabel = new Label(command.description);
                descLabel.setStyle("-fx-font-size: 0.9em; -fx-text-fill: gray;");
                
                content.getChildren().addAll(nameLabel, descLabel);
                setGraphic(content);
            }
        }
    }
}
